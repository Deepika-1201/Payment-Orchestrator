package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.Failure;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.PaymentEvent;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.payment.domain.RefundInitiator;
import com.payments.gateway.payment.domain.RefundStatus;
import com.payments.gateway.payment.domain.TransitionOutcome;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.payment.infrastructure.RefundRepository;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refund lifecycle. Every refund mutation first locks the parent payment row, which serializes refunds per payment
 * and makes {@code Σ active refunds ≤ captured} safe under concurrency (FR-RF1).
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    public record CreateCommand(Long amount, String reason, String merchantRefundId) {
    }

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentStore store;
    private final ProviderClient providerClient;
    private final ProviderRegistry providers;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundService(PaymentRepository payments, RefundRepository refunds, PaymentStore store,
                         ProviderClient providerClient, ProviderRegistry providers, TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.store = store;
        this.providerClient = providerClient;
        this.providers = providers;
        this.tx = tx;
        this.clock = clock;
    }

    public Refund create(String merchantId, String paymentId, CreateCommand command) {
        Instant now = clock.instant();
        Refund refund = tx.execute(status -> {
            Payment payment = payments.lockForMerchant(merchantId, paymentId)
                    .orElseThrow(() -> GatewayException.notFound("Payment", paymentId));
            if (payment.status() != PaymentStatus.SUCCEEDED) {
                throw GatewayException.invalidState("Only succeeded payments can be refunded (status " + payment.status() + ")");
            }
            if (command.merchantRefundId() != null
                    && refunds.findByMerchantRefundId(merchantId, command.merchantRefundId()).isPresent()) {
                throw new GatewayException(ErrorCode.REFUND_ALREADY_EXISTS,
                        "Refund " + command.merchantRefundId() + " already exists for this merchant");
            }
            PaymentAttempt attempt = payment.attempt(payment.succeededAttemptId()).orElseThrow();
            long refundable = payment.amountCaptured() - refunds.sumActiveForAttempt(attempt.id());
            long amount = command.amount() == null ? refundable : command.amount();
            if (amount <= 0 || amount > refundable) {
                throw new GatewayException(ErrorCode.AMOUNT_EXCEEDS_REFUNDABLE,
                        "Requested " + amount + " but only " + refundable + " is refundable");
            }
            if (amount < payment.amountCaptured() && !providers.require(attempt.providerCode()).capabilities().partialRefunds()) {
                throw new GatewayException(ErrorCode.UNSUPPORTED_PAYMENT_METHOD,
                        "Provider " + attempt.providerCode() + " does not support partial refunds");
            }
            Refund created = Refund.initiate(Ids.newId("rfnd"), payment.id(), attempt.id(), merchantId,
                    attempt.providerCode(), Money.of(amount, payment.amount().currency()), command.reason(),
                    command.merchantRefundId(), RefundInitiator.MERCHANT, now);
            payment.touch(now);
            store.save(payment, created, List.of());
            return created;
        });
        return submit(refund.id());
    }

    /** Creates a system refund for a late or duplicate success; must run inside the caller's locked transaction. */
    String createSystemRefund(Payment payment, String attemptId, RefundInitiator initiator, Instant now) {
        PaymentAttempt attempt = payment.attempt(attemptId).orElseThrow();
        long refundable = attempt.amount().amount() - refunds.sumActiveForAttempt(attemptId);
        if (refundable <= 0) {
            return null;
        }
        Refund refund = Refund.initiate(Ids.newId("rfnd"), payment.id(), attemptId, payment.merchantId(),
                attempt.providerCode(), Money.of(refundable, attempt.amount().currency()),
                initiator == RefundInitiator.SYSTEM_LATE_SUCCESS ? "late_success" : "duplicate_success", null, initiator, now);
        store.saveRefund(payment, refund);
        log.warn("System refund {} created for attempt {} of payment {} ({})", refund.id(), attemptId, payment.id(), initiator);
        return refund.id();
    }

    /** Sends (or re-sends) a refund to the PSP. Safe to repeat: the refund id is the PSP idempotency key. */
    public Refund submit(String refundId) {
        Refund refund = refunds.findById(refundId).orElseThrow(() -> GatewayException.notFound("Refund", refundId));
        if (refund.status() != RefundStatus.INITIATED && refund.status() != RefundStatus.UNKNOWN) {
            return refund;
        }
        Payment payment = payments.findById(refund.paymentId()).orElseThrow();
        PaymentAttempt attempt = payment.attempt(refund.attemptId()).orElseThrow();
        try {
            ProviderRefundResult result = providerClient.refund(payment.merchantId(), refund.providerCode(),
                    new RefundRequest(refund.id(), attempt.id(), attempt.providerReference(), refund.amount(), refund.reason()));
            return applyResult(refundId, result, TransitionSource.PROVIDER_RESPONSE);
        } catch (ProviderTimeoutException e) {
            return applyStatus(refundId, RefundStatus.UNKNOWN, null, null, TransitionSource.PROVIDER_RESPONSE);
        } catch (ProviderUnavailableException e) {
            return recordCheck(refundId);
        }
    }

    public Refund applyResult(String refundId, ProviderRefundResult result, TransitionSource source) {
        if (result.outcome() == ProviderRefundResult.Outcome.NOT_FOUND) {
            return refunds.findById(refundId).orElseThrow();
        }
        Failure failure = result.failure() == null ? null : ProviderResults.toFailure(result.failure());
        return applyStatus(refundId, ProviderResults.toRefundStatus(result), result.providerReference(), failure, source);
    }

    /** See {@link PaymentOutcomeService#applyProviderEvent}: a merchant account's events only touch its own refunds. */
    public boolean applyProviderEvent(String providerCode, String merchantScope, ProviderEvent event) {
        Optional<Refund> refund = Optional.empty();
        if (event.providerReference() != null) {
            refund = refunds.findByProviderReference(providerCode, event.providerReference());
        }
        if (refund.isEmpty() && event.merchantReference() != null) {
            refund = refunds.findById(event.merchantReference()).filter(r -> r.providerCode().equals(providerCode));
        }
        if (refund.isEmpty() || event.refund().outcome() == ProviderRefundResult.Outcome.NOT_FOUND) {
            return false;
        }
        if (merchantScope != null && !merchantScope.equals(refund.get().merchantId())) {
            log.warn("Ignored {} event {} from merchant {}'s account: refund {} belongs to another merchant",
                    providerCode, event.eventId(), merchantScope, refund.get().id());
            return false;
        }
        applyResult(refund.get().id(), event.refund(), TransitionSource.PROVIDER_WEBHOOK);
        return true;
    }

    public Refund recordCheck(String refundId) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            Refund current = refunds.findById(refundId).orElseThrow();
            Payment payment = payments.lockById(current.paymentId()).orElseThrow();
            Refund refund = refunds.findById(refundId).orElseThrow();
            refund.afterStatusCheck(now);
            store.save(payment, refund, List.of());
            return refund;
        });
    }

    public Refund get(String merchantId, String refundId) {
        return refunds.findForMerchant(merchantId, refundId).orElseThrow(() -> GatewayException.notFound("Refund", refundId));
    }

    public List<Refund> listForPayment(String merchantId, String paymentId) {
        payments.findForMerchant(merchantId, paymentId).orElseThrow(() -> GatewayException.notFound("Payment", paymentId));
        return refunds.findByPayment(paymentId);
    }

    private Refund applyStatus(String refundId, RefundStatus target, String providerReference, Failure failure,
                               TransitionSource source) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            Refund current = refunds.findById(refundId).orElseThrow();
            Payment payment = payments.lockById(current.paymentId()).orElseThrow();
            Refund refund = refunds.findById(refundId).orElseThrow();
            TransitionOutcome outcome = refund.apply(target, providerReference, failure, source, now);
            if (source == TransitionSource.STATUS_CHECK) {
                refund.afterStatusCheck(now);
            }
            List<PaymentEvent> events = List.of();
            if (outcome == TransitionOutcome.APPLIED && target == RefundStatus.SUCCEEDED) {
                payment.recordRefundSucceeded(refund.attemptId(), refund.amount().amount(), now);
                events = List.of(new PaymentEvent(PaymentEvent.Type.REFUND_SUCCEEDED, refund.id()));
            } else if (outcome == TransitionOutcome.APPLIED && target == RefundStatus.FAILED) {
                events = List.of(new PaymentEvent(PaymentEvent.Type.REFUND_FAILED, refund.id()));
            } else if (outcome == TransitionOutcome.CONFLICT) {
                log.warn("Conflicting refund status {} for refund {} in status {}", target, refundId, refund.status());
            }
            payment.touch(now);
            store.save(payment, refund, events);
            return refund;
        });
    }
}
