package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A chargeback or UPI dispute on a captured attempt (FR-D1, ADR-018). Its own aggregate, never a payment state
 * (ADR-007); created and mutated only while holding the parent payment's row lock. The gateway only records what
 * the PSP reports: evidence is submitted on the PSP's side.
 */
public final class Dispute {

    private final String id;
    private final String paymentId;
    private final String attemptId;
    private final String merchantId;
    private final String providerCode;
    private final String providerDisputeId;
    private final Money amount;
    private final String reason;
    private final Instant createdAt;
    private DisputeStatus status;
    private Instant respondBy;
    private Review review;
    private long version;
    private Instant updatedAt;
    private boolean isNew;
    private final List<StatusChange> changes = new ArrayList<>();
    private final List<PaymentEvent> events = new ArrayList<>();

    private Dispute(DisputeSnapshot s, boolean isNew) {
        this.id = s.id();
        this.paymentId = s.paymentId();
        this.attemptId = s.attemptId();
        this.merchantId = s.merchantId();
        this.providerCode = s.providerCode();
        this.providerDisputeId = s.providerDisputeId();
        this.amount = s.amount();
        this.reason = s.reason();
        this.createdAt = s.createdAt();
        this.status = s.status();
        this.respondBy = s.respondBy();
        this.review = s.review();
        this.version = s.version();
        this.updatedAt = s.updatedAt();
        this.isNew = isNew;
    }

    /** Records a dispute the PSP reported; a first report may already carry a later status. */
    public static Dispute open(String id, PaymentAttempt attempt, String providerDisputeId, Money amount, String reason,
                               DisputeStatus reported, Instant respondBy, TransitionSource source, Instant now) {
        Dispute dispute = new Dispute(new DisputeSnapshot(id, attempt.paymentId(), attempt.id(), attempt.merchantId(),
                attempt.providerCode(), providerDisputeId, amount, reason, DisputeStatus.OPEN, respondBy, Review.NONE, 0,
                now, now), true);
        dispute.changes.add(new StatusChange(StatusChange.Entity.DISPUTE, id, null, DisputeStatus.OPEN.name(), source,
                reason, now));
        dispute.events.add(new PaymentEvent(PaymentEvent.Type.DISPUTE_CREATED, id));
        if (reported != DisputeStatus.OPEN) {
            dispute.apply(reported, null, source, now);
        }
        return dispute;
    }

    public static Dispute rehydrate(DisputeSnapshot snapshot) {
        return new Dispute(snapshot, false);
    }

    public DisputeSnapshot snapshot() {
        return new DisputeSnapshot(id, paymentId, attemptId, merchantId, providerCode, providerDisputeId, amount, reason,
                status, respondBy, review, version, createdAt, updatedAt);
    }

    public TransitionOutcome apply(DisputeStatus target, Instant newRespondBy, TransitionSource source, Instant now) {
        if (newRespondBy != null && !newRespondBy.equals(respondBy) && !status.isFinal()) {
            respondBy = newRespondBy;
            updatedAt = now;
        }
        if (target == status) {
            return TransitionOutcome.NO_OP;
        }
        if (!status.canTransitionTo(target)) {
            if (target.rank() < status.rank()) {
                return TransitionOutcome.NO_OP;
            }
            review = review.flag(Review.PROVIDER_CONFLICT, now);
            updatedAt = now;
            return TransitionOutcome.CONFLICT;
        }
        DisputeStatus before = status;
        status = target;
        updatedAt = now;
        changes.add(new StatusChange(StatusChange.Entity.DISPUTE, id, before.name(), target.name(), source, null, now));
        events.add(new PaymentEvent(switch (target) {
            case WON -> PaymentEvent.Type.DISPUTE_WON;
            case LOST -> PaymentEvent.Type.DISPUTE_LOST;
            default -> PaymentEvent.Type.DISPUTE_UPDATED;
        }, id));
        return TransitionOutcome.APPLIED;
    }

    public void flagForReview(String reviewReason, Instant now) {
        review = review.flag(reviewReason, now);
        updatedAt = now;
    }

    /** Acknowledges the review (ADR-016); the dispute's money state is untouched. */
    public void resolveReview(Instant now) {
        if (!review.open()) {
            throw GatewayException.invalidState("Dispute is not awaiting review");
        }
        review = review.resolve();
        updatedAt = now;
    }

    /** Until a dispute is won, the PSP holds its amount back from the merchant, so it cannot also be refunded. */
    public boolean holdsFunds() {
        return status != DisputeStatus.WON;
    }

    public void markPersisted() {
        if (!isNew) {
            version++;
        }
        isNew = false;
    }

    public List<StatusChange> pullChanges() {
        List<StatusChange> pulled = List.copyOf(changes);
        changes.clear();
        return pulled;
    }

    public List<PaymentEvent> pullEvents() {
        List<PaymentEvent> pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    public boolean isNew() {
        return isNew;
    }

    public String id() {
        return id;
    }

    public String paymentId() {
        return paymentId;
    }

    public String attemptId() {
        return attemptId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String providerCode() {
        return providerCode;
    }

    public String providerDisputeId() {
        return providerDisputeId;
    }

    public Money amount() {
        return amount;
    }

    public String reason() {
        return reason;
    }

    public DisputeStatus status() {
        return status;
    }

    public Instant respondBy() {
        return respondBy;
    }

    public Review review() {
        return review;
    }

    public boolean needsReview() {
        return review.open();
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
