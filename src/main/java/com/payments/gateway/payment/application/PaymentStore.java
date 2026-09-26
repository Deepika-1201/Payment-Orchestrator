package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentEvent;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.payment.domain.RefundStatus;
import com.payments.gateway.payment.domain.StatusChange;
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
    private final TransitionLog transitions;
    private final PaymentEventPublisher events;
    private final ProviderHealthTracker health;
    private final MeterRegistry meters;

    public PaymentStore(PaymentRepository payments, RefundRepository refunds, TransitionLog transitions,
                        PaymentEventPublisher events, ProviderHealthTracker health, MeterRegistry meters) {
        this.payments = payments;
        this.refunds = refunds;
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

    private void publishCaptures(Payment payment, List<StatusChange> changes) {
        for (StatusChange change : changes) {
            if (change.entity() == StatusChange.Entity.ATTEMPT && AttemptStatus.SUCCEEDED.name().equals(change.toStatus())) {
                payment.attempt(change.entityId()).ifPresent(attempt -> events.publishFundsMovement(new FundsMovement(
                        FundsMovement.Type.CAPTURE, payment.merchantId(), attempt.providerCode(), attempt.id(),
                        payment.id(), attempt.amount(), change.occurredAt())));
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
