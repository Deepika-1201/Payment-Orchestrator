package com.payments.gateway.payment.application;

import com.payments.gateway.merchant.Merchant;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.Customer;
import com.payments.gateway.payment.domain.Failure;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.payment.domain.RiskAssessment;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.spi.InitiatePaymentRequest;
import com.payments.gateway.provider.spi.ProviderCredentialsException;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.risk.RiskContext;
import com.payments.gateway.risk.RiskDecision;
import com.payments.gateway.risk.RiskEngine;
import com.payments.gateway.risk.RiskProperties;
import com.payments.gateway.routing.RoutingContext;
import com.payments.gateway.routing.RoutingDecision;
import com.payments.gateway.routing.RoutingEngine;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.web.Mdc;
import com.payments.gateway.shared.web.WireEnums;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment orchestration (LLD §4.1). Transactions are short and never span a PSP call: the attempt is persisted as
 * INITIATED before the call, and the outcome is applied in a second transaction.
 */
@Service
public class PaymentService {

    public record CreateCommand(Money amount, String merchantOrderId, CaptureMethod captureMethod, String description,
                                Customer customer, Map<String, String> metadata, Duration expiresIn) {
    }

    public record ConfirmCommand(PaymentMethod method, String returnUrl, String clientIp, String userAgent,
                                 String deviceId) {
    }

    private record Started(Payment payment, String attemptId, List<String> candidates, boolean expired) {
    }

    private final PaymentRepository payments;
    private final PaymentStore store;
    private final PaymentOutcomeService outcomes;
    private final MerchantDirectory merchants;
    private final RiskEngine risk;
    private final RiskProperties riskProperties;
    private final RoutingEngine routing;
    private final ProviderClient providerClient;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public PaymentService(PaymentRepository payments, PaymentStore store, PaymentOutcomeService outcomes,
                          MerchantDirectory merchants, RiskEngine risk, RiskProperties riskProperties,
                          RoutingEngine routing, ProviderClient providerClient, PaymentProperties properties,
                          TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.store = store;
        this.outcomes = outcomes;
        this.merchants = merchants;
        this.risk = risk;
        this.riskProperties = riskProperties;
        this.routing = routing;
        this.providerClient = providerClient;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    public Payment create(String merchantId, CreateCommand command) {
        if (!properties.supportedCurrencies().contains(command.amount().currency())) {
            throw new GatewayException(ErrorCode.UNSUPPORTED_CURRENCY,
                    "Supported currencies: " + String.join(", ", properties.supportedCurrencies()));
        }
        Merchant merchant = merchants.require(merchantId);
        Instant now = clock.instant();
        Duration expiry = command.expiresIn() != null ? command.expiresIn() : merchant.paymentExpiry();
        Payment payment = Payment.create(Ids.newId("pay"), merchantId, command.merchantOrderId(), command.amount(),
                command.captureMethod(), command.description(), command.customer(), command.metadata(),
                now.plus(expiry), now);
        MDC.put(Mdc.PAYMENT_ID, payment.id());
        tx.executeWithoutResult(status -> store.save(payment));
        // Not "pg.payments.created": Prometheus reserves the _created suffix, so it would export as pg_payments_total anyway.
        meters.counter("pg.payments").increment();
        return payment;
    }

    public Payment get(String merchantId, String paymentId) {
        return payments.findForMerchant(merchantId, paymentId)
                .orElseThrow(() -> GatewayException.notFound("Payment", paymentId));
    }

    public Payment confirm(String merchantId, String paymentId, ConfirmCommand command) {
        MDC.put(Mdc.PAYMENT_ID, paymentId);
        Set<String> linkedProviders = merchants.activeProviders(merchantId);
        Instant now = clock.instant();
        Payment current = payments.findForMerchant(merchantId, paymentId)
                .orElseThrow(() -> GatewayException.notFound("Payment", paymentId));
        // Risk rules may call an external vendor, so they run before the row lock is taken (ADR-016).
        RiskDecision decision = assessRisk(current, command, now);
        Started started = tx.execute(status -> {
            Payment payment = payments.lockForMerchant(merchantId, paymentId)
                    .orElseThrow(() -> GatewayException.notFound("Payment", paymentId));
            if (payment.status() == PaymentStatus.REQUIRES_PAYMENT_METHOD && !now.isBefore(payment.expiresAt())) {
                payment.expire(now, properties.processingGrace());
                store.save(payment);
                return new Started(payment, null, List.of(), true);
            }
            payment.ensureConfirmable(properties.maxAttempts(), now);
            if (decision.outcome() == RiskDecision.Outcome.BLOCK) {
                payment.blockByRisk(decision.reasons(), now);
                store.save(payment);
                return new Started(payment, null, List.of(), false);
            }
            RoutingDecision route = routing.route(new RoutingContext(merchantId, command.method(), payment.amount(),
                    payment.captureMethod(), linkedProviders));
            if (route.isEmpty()) {
                throw noRoute(route, command.method());
            }
            PaymentAttempt attempt = payment.startAttempt(Ids.newId("att"), command.method(), route.providers().getFirst(),
                    route.ruleId(), properties.maxAttempts(), now);
            payment.recordRisk(attempt.id(), new RiskAssessment(decision.outcome().name(), decision.reasons()), now);
            store.save(payment);
            return new Started(payment, attempt.id(), route.providers(), false);
        });
        if (started.expired()) {
            throw GatewayException.invalidState("Payment has expired");
        }
        if (started.attemptId() == null) {
            return started.payment();
        }
        return executeAttempt(started.payment(), started.attemptId(), started.candidates(), command);
    }

    public Payment capture(String merchantId, String paymentId, Long amount) {
        MDC.put(Mdc.PAYMENT_ID, paymentId);
        Instant now = clock.instant();
        String attemptId = tx.execute(status -> {
            Payment payment = payments.lockForMerchant(merchantId, paymentId)
                    .orElseThrow(() -> GatewayException.notFound("Payment", paymentId));
            PaymentAttempt attempt = payment.requestCapture(amount, now);
            store.save(payment);
            return attempt.id();
        });
        return outcomes.performCapture(paymentId, attemptId);
    }

    public Payment cancel(String merchantId, String paymentId, String reason) {
        MDC.put(Mdc.PAYMENT_ID, paymentId);
        Instant now = clock.instant();
        Optional<String> voidAttemptId = tx.execute(status -> {
            Payment payment = payments.lockForMerchant(merchantId, paymentId)
                    .orElseThrow(() -> GatewayException.notFound("Payment", paymentId));
            Optional<String> result = payment.cancel(reason, now);
            store.save(payment);
            return result;
        });
        voidAttemptId.ifPresent(attemptId -> outcomes.performVoid(paymentId, attemptId));
        return payments.findById(paymentId).orElseThrow();
    }

    private Payment executeAttempt(Payment payment, String attemptId, List<String> candidates, ConfirmCommand command) {
        String currentAttemptId = attemptId;
        int index = 0;
        while (true) {
            String provider = candidates.get(index);
            InitiatePaymentRequest request = new InitiatePaymentRequest(currentAttemptId, payment.merchantId(),
                    payment.amount(), command.method(), payment.captureMethod(), payment.description(),
                    payment.customer().email(), payment.customer().phone(), command.returnUrl(), command.clientIp());
            try {
                ProviderPaymentResult result = providerClient.initiate(payment.merchantId(), provider, request);
                if (result.outcome() == ProviderPaymentResult.Outcome.NOT_FOUND) {
                    throw new ProviderTimeoutException(provider, "provider returned NOT_FOUND for initiate");
                }
                return outcomes.apply(payment.id(), currentAttemptId, ProviderResults.toUpdate(result),
                        TransitionSource.PROVIDER_RESPONSE);
            } catch (ProviderUnavailableException e) {
                index++;
                String next = index < candidates.size() ? candidates.get(index) : null;
                Failure failure = e instanceof ProviderCredentialsException
                        ? new Failure("provider_credentials_rejected", FailureCategory.VALIDATION, e.getMessage())
                        : new Failure("provider_unavailable", FailureCategory.PROVIDER_UNAVAILABLE, e.getMessage());
                PaymentOutcomeService.FailoverResult failover = outcomes.failover(payment.id(), currentAttemptId, next, failure);
                if (failover.newAttemptId() == null) {
                    return failover.payment();
                }
                currentAttemptId = failover.newAttemptId();
            } catch (ProviderTimeoutException e) {
                return outcomes.apply(payment.id(), currentAttemptId, AttemptUpdate.of(AttemptStatus.UNKNOWN),
                        TransitionSource.PROVIDER_RESPONSE);
            }
        }
    }

    private RiskDecision assessRisk(Payment payment, ConfirmCommand command, Instant now) {
        if (payment.status() != PaymentStatus.REQUIRES_PAYMENT_METHOD || !now.isBefore(payment.expiresAt())) {
            return RiskDecision.allow(); // the locked re-check reports why it cannot be confirmed
        }
        return risk.evaluate(riskContext(payment, command, now));
    }

    private RiskContext riskContext(Payment payment, ConfirmCommand command, Instant now) {
        String customerReference = payment.customer().reference();
        int recentAttempts = customerReference == null ? 0 : payments.countRecentAttemptsByCustomer(
                payment.merchantId(), customerReference, now.minus(riskProperties.velocityWindow()));
        return new RiskContext(payment.merchantId(), payment.id(), payment.amount(), command.method(),
                customerReference, payment.customer().email(), command.clientIp(), command.deviceId(), recentAttempts);
    }

    private static GatewayException noRoute(RoutingDecision route, PaymentMethod method) {
        if (route.reason() == RoutingDecision.Reason.ALL_UNAVAILABLE) {
            return new GatewayException(ErrorCode.NO_PROVIDER_AVAILABLE,
                    "All providers for this payment method are temporarily unavailable", List.of(), 5);
        }
        return new GatewayException(ErrorCode.UNSUPPORTED_PAYMENT_METHOD,
                "No linked provider supports " + WireEnums.wire(method.type())
                        + (method.upiFlow() == null ? "" : " " + WireEnums.wire(method.upiFlow()))
                        + " for this amount, currency and capture method");
    }
}
