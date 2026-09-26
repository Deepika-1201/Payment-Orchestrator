package com.payments.gateway.payment.application;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.payment.domain.AttemptApplyResult;
import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.Failure;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.PaymentPolicy;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository.AttemptLocator;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.VoidRequest;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.FailureCategory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies PSP evidence (synchronous responses, webhooks, status checks) to payments under the payment row lock,
 * then runs follow-ups after commit: automatic capture, voids and system refunds (LLD §4.2).
 */
@Service
public class PaymentOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeService.class);

    record Applied(Payment payment, AttemptApplyResult result, String refundId) {
    }

    public record FailoverResult(Payment payment, String newAttemptId) {
    }

    private final PaymentRepository payments;
    private final PaymentStore store;
    private final RefundService refunds;
    private final ProviderClient providerClient;
    private final ProviderRegistry providers;
    private final MerchantDirectory merchants;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public PaymentOutcomeService(PaymentRepository payments, PaymentStore store, RefundService refunds,
                                 ProviderClient providerClient, ProviderRegistry providers, MerchantDirectory merchants,
                                 PaymentProperties properties, TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.store = store;
        this.refunds = refunds;
        this.providerClient = providerClient;
        this.providers = providers;
        this.merchants = merchants;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    public Payment apply(String paymentId, String attemptId, AttemptUpdate update, TransitionSource source) {
        Instant now = clock.instant();
        Applied applied = tx.execute(status -> {
            Payment payment = payments.lockById(paymentId).orElseThrow(() -> GatewayException.notFound("Payment", paymentId));
            AttemptApplyResult result = payment.applyAttemptUpdate(attemptId, update, source, policyFor(payment), now);
            if (source == TransitionSource.STATUS_CHECK) {
                payment.recordStatusCheck(attemptId, now);
            }
            String refundId = result.refundRequired()
                    ? refunds.createSystemRefund(payment, attemptId, result.refundInitiator(), now)
                    : null;
            store.save(payment);
            return new Applied(payment, result, refundId);
        });
        observe(applied, update, source);
        return runFollowUps(applied);
    }

    public boolean applyProviderEvent(String providerCode, ProviderEvent event) {
        if (event.payment().outcome() == ProviderPaymentResult.Outcome.NOT_FOUND) {
            return false;
        }
        Optional<AttemptLocator> locator = Optional.empty();
        if (event.providerReference() != null) {
            locator = payments.findAttemptByProviderReference(providerCode, event.providerReference());
        }
        if (locator.isEmpty() && event.merchantReference() != null) {
            locator = payments.findAttemptById(event.merchantReference()).filter(l -> l.providerCode().equals(providerCode));
        }
        if (locator.isEmpty()) {
            return false;
        }
        apply(locator.get().paymentId(), locator.get().attemptId(), ProviderResults.toUpdate(event.payment()),
                TransitionSource.PROVIDER_WEBHOOK);
        return true;
    }

    /** The PSP definitely did not process the attempt: fail it and move to the next candidate, if any. */
    public FailoverResult failover(String paymentId, String attemptId, String nextProvider, String reason) {
        Instant now = clock.instant();
        Failure failure = new Failure("provider_unavailable", FailureCategory.PROVIDER_UNAVAILABLE, reason);
        return tx.execute(status -> {
            Payment payment = payments.lockById(paymentId).orElseThrow();
            PaymentAttempt attempt = payment.attempt(attemptId).orElseThrow();
            if (attempt.status() != AttemptStatus.INITIATED || attempt != payment.latestAttempt()) {
                return new FailoverResult(payment, null);
            }
            String newAttemptId = null;
            if (nextProvider != null) {
                newAttemptId = payment.failover(attemptId, failure, Ids.newId("att"), nextProvider, now).id();
            } else {
                payment.applyAttemptUpdate(attemptId, new AttemptUpdate(AttemptStatus.FAILED, null, null, failure, null),
                        TransitionSource.PROVIDER_RESPONSE, policyFor(payment), now);
            }
            store.save(payment);
            return new FailoverResult(payment, newAttemptId);
        });
    }

    public Payment performCapture(String paymentId, String attemptId) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        PaymentAttempt attempt = payment.attempt(attemptId).orElseThrow();
        if (attempt.status() != AttemptStatus.CAPTURE_PENDING) {
            return payment;
        }
        try {
            ProviderPaymentResult result = providerClient.capture(attempt.providerCode(),
                    new CaptureRequest(attempt.id(), attempt.providerReference(), attempt.amount()));
            return switch (result.outcome()) {
                case SUCCEEDED -> apply(paymentId, attemptId, ProviderResults.toUpdate(result), TransitionSource.PROVIDER_RESPONSE);
                case FAILED -> rejectCapture(paymentId, attemptId, ProviderResults.toFailure(result.failure()));
                default -> payment;
            };
        } catch (ProviderTimeoutException | ProviderUnavailableException e) {
            log.warn("Capture of attempt {} not confirmed; the status resolver will retry", attemptId);
            return payment;
        }
    }

    public void performVoid(String paymentId, String attemptId) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        PaymentAttempt attempt = payment.attempt(attemptId).orElseThrow();
        if (attempt.status() != AttemptStatus.AUTHORIZED || !attempt.voidRequested()) {
            return;
        }
        if (!providers.require(attempt.providerCode()).capabilities().voidSupported()) {
            log.warn("Provider {} cannot void; authorization of attempt {} will lapse at the issuer", attempt.providerCode(), attemptId);
            recordCheck(paymentId, attemptId);
            return;
        }
        try {
            ProviderPaymentResult result = providerClient.voidAuthorization(attempt.providerCode(),
                    new VoidRequest(attempt.id(), attempt.providerReference()));
            if (result.outcome() == ProviderPaymentResult.Outcome.VOIDED) {
                apply(paymentId, attemptId, ProviderResults.toUpdate(result), TransitionSource.PROVIDER_RESPONSE);
                return;
            }
        } catch (ProviderTimeoutException | ProviderUnavailableException e) {
            log.warn("Void of attempt {} not confirmed; the status resolver will retry", attemptId);
        }
        recordCheck(paymentId, attemptId);
    }

    public void recordCheck(String paymentId, String attemptId) {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            Payment payment = payments.lockById(paymentId).orElseThrow();
            payment.recordStatusCheck(attemptId, now);
            store.save(payment);
        });
    }

    private Payment rejectCapture(String paymentId, String attemptId, Failure failure) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            Payment payment = payments.lockById(paymentId).orElseThrow();
            payment.rejectCapture(attemptId, failure, now);
            store.save(payment);
            return payment;
        });
    }

    private Payment runFollowUps(Applied applied) {
        AttemptApplyResult result = applied.result();
        String paymentId = applied.payment().id();
        boolean followedUp = false;
        if (result.captureRequired()) {
            performCapture(paymentId, result.attemptId());
            followedUp = true;
        }
        if (result.voidRequired()) {
            performVoid(paymentId, result.attemptId());
            followedUp = true;
        }
        if (applied.refundId() != null) {
            refunds.submit(applied.refundId());
            followedUp = true;
        }
        return followedUp ? payments.findById(paymentId).orElseThrow() : applied.payment();
    }

    private void observe(Applied applied, AttemptUpdate update, TransitionSource source) {
        AttemptApplyResult result = applied.result();
        switch (result.kind()) {
            case CONFLICT -> {
                meters.counter("pg.provider.conflicts", "source", source.name()).increment();
                log.warn("Conflicting PSP evidence {} for attempt {} ignored and flagged for review", update.status(), result.attemptId());
            }
            case AMOUNT_MISMATCH -> {
                meters.counter("pg.provider.amount_mismatches", "source", source.name()).increment();
                log.error("PSP reported amount {} for attempt {} does not match; not applied, flagged for review",
                        update.reportedAmount(), result.attemptId());
            }
            default -> {
            }
        }
        if (result.refundRequired()) {
            meters.counter("pg.payments.late_success", "action", result.refundInitiator().name().toLowerCase(java.util.Locale.ROOT)).increment();
        } else if (result.lateSuccessAccepted()) {
            meters.counter("pg.payments.late_success", "action", "accepted").increment();
        }
    }

    private PaymentPolicy policyFor(Payment payment) {
        return new PaymentPolicy(properties.maxAttempts(), properties.authorizationTtl(),
                merchants.require(payment.merchantId()).acceptsLateSuccess());
    }
}
