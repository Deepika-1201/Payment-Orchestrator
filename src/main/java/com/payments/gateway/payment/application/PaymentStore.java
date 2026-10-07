package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.Dispute;
import com.payments.gateway.payment.domain.DisputeStatus;
import com.payments.gateway.payment.domain.Mandate;
import com.payments.gateway.payment.domain.MandateDebit;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentEvent;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.payment.domain.RefundStatus;
import com.payments.gateway.payment.domain.StatusChange;
import com.payments.gateway.payment.infrastructure.DisputeRepository;
import com.payments.gateway.payment.infrastructure.MandateRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.payment.infrastructure.RefundRepository;
import com.payments.gateway.payment.infrastructure.TransitionLog;
import com.payments.gateway.provider.ProviderHealthTracker;
import com.payments.gateway.shared.events.FundsMovement;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit-of-work helper: persists the aggregate, appends transitions, records outbox events in the same transaction
 * and, after commit, feeds routing health and metrics.
 */
@Component
public class PaymentStore {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final DisputeRepository disputes;
    private final MandateRepository mandates;
    private final MandateProperties mandateProperties;
    private final TransitionLog transitions;
    private final PaymentEventPublisher events;
    private final ProviderHealthTracker health;
    private final MeterRegistry meters;

    public PaymentStore(PaymentRepository payments, RefundRepository refunds, DisputeRepository disputes,
                        MandateRepository mandates, MandateProperties mandateProperties,
                        TransitionLog transitions, PaymentEventPublisher events, ProviderHealthTracker health,
                        MeterRegistry meters) {
        this.payments = payments;
        this.refunds = refunds;
        this.disputes = disputes;
        this.mandates = mandates;
        this.mandateProperties = mandateProperties;
        this.transitions = transitions;
        this.events = events;
        this.health = health;
        this.meters = meters;
    }

    public void save(Payment payment) {
        save(payment, null, List.of());
    }

    public void save(Payment payment, Refund refund, List<PaymentEvent> refundEvents) {
        List<StatusChange> changes = payment.pullChanges();
        List<PaymentEvent> paymentEvents = payment.pullEvents();
        payments.save(payment);
        transitions.append(payment.id(), payment.merchantId(), changes);
        publishCaptures(payment, changes);
        followMandateDebit(payment, changes);
        if (refund != null) {
            saveRefund(payment, refund);
        }
        events.publishPaymentEvents(payment, paymentEvents);
        if (refund != null) {
            events.publishRefundEvents(refund, refundEvents);
        }
        afterCommit(() -> recordAttemptOutcomes(payment, changes));
    }

    public void saveRefund(Payment payment, Refund refund) {
        List<StatusChange> changes = refund.pullChanges();
        refunds.save(refund);
        transitions.append(payment.id(), payment.merchantId(), changes);
        for (StatusChange change : changes) {
            if (RefundStatus.SUCCEEDED.name().equals(change.toStatus())) {
                events.publishFundsMovement(new FundsMovement(FundsMovement.Type.REFUND, refund.merchantId(),
                        refund.providerCode(), refund.id(), payment.id(), refund.amount(), change.occurredAt()));
            }
        }
    }

    /**
     * Saves a dispute under its payment's lock. Opening withholds the disputed amount (chargeback posting) and
     * winning returns it (reversal); losing moves nothing further (ADR-018).
     */
    public void saveDispute(Payment payment, Dispute dispute) {
        List<StatusChange> changes = dispute.pullChanges();
        List<PaymentEvent> disputeEvents = dispute.pullEvents();
        disputes.save(dispute);
        transitions.append(payment.id(), payment.merchantId(), changes);
        for (StatusChange change : changes) {
            FundsMovement.Type movement = change.fromStatus() == null ? FundsMovement.Type.CHARGEBACK
                    : DisputeStatus.WON.name().equals(change.toStatus()) ? FundsMovement.Type.CHARGEBACK_REVERSAL : null;
            if (movement != null) {
                events.publishFundsMovement(new FundsMovement(movement, dispute.merchantId(), dispute.providerCode(),
                        dispute.id(), payment.id(), dispute.amount(), change.occurredAt()));
            }
        }
        events.publishDisputeEvents(dispute, disputeEvents);
    }

    /** Saves a mandate under its row lock, with its transitions and merchant events (ADR-035). */
    public void saveMandate(Mandate mandate) {
        List<StatusChange> changes = mandate.pullChanges();
        List<PaymentEvent> mandateEvents = mandate.pullEvents();
        mandates.save(mandate);
        mandates.appendTransitions(mandate.id(), mandate.merchantId(), changes);
        events.publishMandateEvents(mandate, mandateEvents);
    }

    /** Saves a debit under its payment's lock (lock order mandate → payment → debit). */
    public void saveDebit(MandateDebit debit) {
        List<StatusChange> changes = debit.pullChanges();
        mandates.save(debit);
        mandates.appendTransitions(debit.mandateId(), debit.merchantId(), changes);
    }

    /**
     * A debit ends only through its payment (LLD §18.5): when the payment's status changes, the debit follows in this
     * transaction, under the payment lock already held. A failure also has the mandate checked at the PSP after commit.
     */
    private void followMandateDebit(Payment payment, List<StatusChange> changes) {
        if (payment.mandateId() == null || changes.stream().noneMatch(c -> c.entity() == StatusChange.Entity.PAYMENT)) {
            return;
        }
        mandates.lockDebitByPayment(payment.id()).ifPresent(debit -> {
            if (debit.followPayment(payment.status(), payment.failureCode(), payment.failureMessage(),
                    mandateProperties.retryInterval(), mandateProperties.notifyAhead(), payment.updatedAt())) {
                saveDebit(debit);
                if (payment.status() == PaymentStatus.FAILED || payment.status() == PaymentStatus.REQUIRES_PAYMENT_METHOD) {
                    afterCommit(() -> mandates.requestCheck(debit.mandateId(), payment.updatedAt()));
                }
            }
        });
    }

    private void publishCaptures(Payment payment, List<StatusChange> changes) {
        for (StatusChange change : changes) {
            if (change.entity() == StatusChange.Entity.ATTEMPT && AttemptStatus.SUCCEEDED.name().equals(change.toStatus())) {
                payment.attempt(change.entityId()).ifPresent(attempt -> events.publishFundsMovement(new FundsMovement(
                        FundsMovement.Type.CAPTURE, payment.merchantId(), attempt.providerCode(), attempt.id(),
                        payment.id(), attempt.capturedAmount(), change.occurredAt())));
            }
        }
    }

    private void recordAttemptOutcomes(Payment payment, List<StatusChange> changes) {
        for (StatusChange change : changes) {
            boolean succeeded = AttemptStatus.SUCCEEDED.name().equals(change.toStatus());
            boolean failed = AttemptStatus.FAILED.name().equals(change.toStatus());
            if (change.entity() != StatusChange.Entity.ATTEMPT || !(succeeded || failed)) {
                continue;
            }
            payment.attempt(change.entityId()).ifPresent(attempt -> {
                health.recordAttemptOutcome(attempt.providerCode(), attempt.method().type(), succeeded,
                        attempt.failure() == null ? null : attempt.failure().category());
                meters.counter("pg.payment.attempts",
                        "provider", attempt.providerCode(),
                        "method", attempt.method().type().name().toLowerCase(Locale.ROOT),
                        "outcome", change.toStatus().toLowerCase(Locale.ROOT)).increment();
            });
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
