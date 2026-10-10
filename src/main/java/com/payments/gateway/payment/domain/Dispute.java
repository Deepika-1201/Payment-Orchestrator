package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A chargeback or UPI dispute on a captured attempt (FR-D1, ADR-018). Its own aggregate, never a payment state
 * (ADR-007); created and mutated only while holding the parent payment's row lock. The PSP reports its status; the
 * merchant may answer it once through the gateway, by contesting or accepting it (ADR-039).
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
    private MerchantResponse response;
    private Instant evidenceDueNotifiedAt;
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
        this.response = s.response();
        this.evidenceDueNotifiedAt = s.evidenceDueNotifiedAt();
        this.version = s.version();
        this.updatedAt = s.updatedAt();
        this.isNew = isNew;
    }

    /** Records a dispute the PSP reported; a first report may already carry a later status. */
    public static Dispute open(String id, PaymentAttempt attempt, String providerDisputeId, Money amount, String reason,
                               DisputeStatus reported, Instant respondBy, TransitionSource source, Instant now) {
        Dispute dispute = new Dispute(new DisputeSnapshot(id, attempt.paymentId(), attempt.id(), attempt.merchantId(),
                attempt.providerCode(), providerDisputeId, amount, reason, DisputeStatus.OPEN, respondBy, Review.NONE, 0,
                now, now, null, null), true);
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
                status, respondBy, review, version, createdAt, updatedAt, response, evidenceDueNotifiedAt);
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

    public void touch(Instant now) {
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

    /** Open, before the deadline, and no response pending or sent: the merchant may still answer (ADR-039). */
    public boolean openForResponse(Instant now) {
        return status == DisputeStatus.OPEN && (respondBy == null || now.isBefore(respondBy))
                && (response == null || response.status() == MerchantResponse.Status.FAILED);
    }

    /** Records the merchant's answer as pending; it is delivered to the PSP after commit. */
    public void requestResponse(MerchantResponse.Type type, String statement, List<String> fileIds, Instant firstAttemptAt,
                                Instant now) {
        requireOpenForResponse(now);
        response = MerchantResponse.pending(type, statement, fileIds, now, firstAttemptAt);
        updatedAt = now;
    }

    public void requireOpenForResponse(Instant now) {
        if (!openForResponse(now)) {
            throw new GatewayException(ErrorCode.DISPUTE_INVALID_STATE,
                    "Dispute " + id + " cannot take a response: " + whyClosed(now));
        }
    }

    /** The PSP took the response. The dispute status it reports is applied separately. */
    public void responseSent(Instant now) {
        requirePendingResponse();
        response = response.sent(now);
        updatedAt = now;
    }

    /** Refused by the PSP, or the deadline passed before delivery: the merchant may answer again while it can. */
    public void responseFailed(String failure, Instant now) {
        requirePendingResponse();
        response = response.failed(failure);
        review = review.flag(Review.RESPONSE_FAILED, now);
        events.add(new PaymentEvent(PaymentEvent.Type.DISPUTE_RESPONSE_FAILED, id));
        updatedAt = now;
    }

    public void retryResponseAt(Instant next, Instant now) {
        requirePendingResponse();
        response = response.retryAt(next);
        updatedAt = now;
    }

    /** Open, with its deadline within {@code notice} (or passed), and nothing pending or sent. */
    public boolean evidenceDue(Instant now, Duration notice) {
        return status == DisputeStatus.OPEN && respondBy != null && !respondBy.isAfter(now.plus(notice))
                && (response == null || response.status() == MerchantResponse.Status.FAILED);
    }

    /** Sends {@code dispute.evidence_due} once per dispute; returns whether it did. */
    public boolean notifyEvidenceDue(Instant now, Duration notice) {
        if (evidenceDueNotifiedAt != null || !evidenceDue(now, notice)) {
            return false;
        }
        evidenceDueNotifiedAt = now;
        events.add(new PaymentEvent(PaymentEvent.Type.DISPUTE_EVIDENCE_DUE, id));
        updatedAt = now;
        return true;
    }

    private void requirePendingResponse() {
        if (response == null || response.status() != MerchantResponse.Status.PENDING) {
            throw new IllegalStateException("dispute " + id + " has no pending response");
        }
    }

    private String whyClosed(Instant now) {
        if (status != DisputeStatus.OPEN) {
            return "it is " + status.name().toLowerCase(Locale.ROOT);
        }
        if (respondBy != null && !now.isBefore(respondBy)) {
            return "its deadline " + respondBy + " has passed";
        }
        return "a response is already " + response.status().name().toLowerCase(Locale.ROOT);
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

    public MerchantResponse response() {
        return response;
    }

    public Instant evidenceDueNotifiedAt() {
        return evidenceDueNotifiedAt;
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
