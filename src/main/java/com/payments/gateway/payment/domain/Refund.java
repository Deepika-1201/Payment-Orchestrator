package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Refund aggregate. Created and mutated only while holding the parent payment's row lock. */
public final class Refund {

    private final String id;
    private final String paymentId;
    private final String attemptId;
    private final String merchantId;
    private final String providerCode;
    private final Money amount;
    private final String reason;
    private final String merchantRefundId;
    private final RefundInitiator initiatedBy;
    private final String creditId;
    private final Instant createdAt;
    private RefundStatus status;
    private String providerReference;
    private Failure failure;
    private Instant nextStatusCheckAt;
    private int statusCheckCount;
    private Review review;
    private long version;
    private Instant updatedAt;
    private boolean isNew;
    private final List<StatusChange> changes = new ArrayList<>();

    private Refund(RefundSnapshot s, boolean isNew) {
        this.id = s.id();
        this.paymentId = s.paymentId();
        this.attemptId = s.attemptId();
        this.merchantId = s.merchantId();
        this.providerCode = s.providerCode();
        this.amount = s.amount();
        this.reason = s.reason();
        this.merchantRefundId = s.merchantRefundId();
        this.initiatedBy = s.initiatedBy();
        this.creditId = s.creditId();
        this.createdAt = s.createdAt();
        this.status = s.status();
        this.providerReference = s.providerReference();
        this.failure = s.failure();
        this.nextStatusCheckAt = s.nextStatusCheckAt();
        this.statusCheckCount = s.statusCheckCount();
        this.review = s.review();
        this.version = s.version();
        this.updatedAt = s.updatedAt();
        this.isNew = isNew;
    }

    public static Refund initiate(String id, String paymentId, String attemptId, String merchantId, String providerCode,
                                  Money amount, String reason, String merchantRefundId, RefundInitiator initiatedBy,
                                  Instant now) {
        return initiate(id, paymentId, attemptId, merchantId, providerCode, amount, reason, merchantRefundId, initiatedBy,
                null, now);
    }

    public static Refund initiate(String id, String paymentId, String attemptId, String merchantId, String providerCode,
                                  Money amount, String reason, String merchantRefundId, RefundInitiator initiatedBy,
                                  String creditId, Instant now) {
        if (initiatedBy == RefundInitiator.SYSTEM_CREDIT_RETURN && creditId == null) {
            throw new IllegalArgumentException("a credit return needs its credit");
        }
        Refund refund = new Refund(new RefundSnapshot(id, paymentId, attemptId, merchantId, providerCode, amount,
                RefundStatus.INITIATED, reason, merchantRefundId, initiatedBy, null, null,
                now.plus(Duration.ofSeconds(30)), 0, Review.NONE, 0, now, now, creditId), true);
        refund.changes.add(new StatusChange(StatusChange.Entity.REFUND, id, null, RefundStatus.INITIATED.name(),
                initiatedBy == RefundInitiator.MERCHANT ? TransitionSource.API : TransitionSource.SYSTEM,
                initiatedBy.name().toLowerCase(java.util.Locale.ROOT), now));
        return refund;
    }

    public static Refund rehydrate(RefundSnapshot snapshot) {
        return new Refund(snapshot, false);
    }

    public RefundSnapshot snapshot() {
        return new RefundSnapshot(id, paymentId, attemptId, merchantId, providerCode, amount, status, reason,
                merchantRefundId, initiatedBy, providerReference, failure, nextStatusCheckAt, statusCheckCount,
                review, version, createdAt, updatedAt, creditId);
    }

    public TransitionOutcome apply(RefundStatus target, String reference, Failure newFailure, TransitionSource source,
                                   Instant now) {
        if (reference != null && providerReference == null) {
            providerReference = reference;
            updatedAt = now;
        }
        if (target == status) {
            return TransitionOutcome.NO_OP;
        }
        if (!status.canTransitionTo(target)) {
            boolean stale = target.rank() < status.rank();
            if (!stale) {
                review = review.flag(Review.PROVIDER_CONFLICT, now);
                updatedAt = now;
            }
            return stale ? TransitionOutcome.NO_OP : TransitionOutcome.CONFLICT;
        }
        if (status == RefundStatus.FAILED && !source.isProviderEvidence()) {
            return TransitionOutcome.CONFLICT;
        }
        RefundStatus before = status;
        status = target;
        failure = target == RefundStatus.FAILED ? newFailure : null;
        if (target.isFinal()) {
            nextStatusCheckAt = null;
        } else {
            Instant soonest = now.plus(StatusCheckSchedule.delay(target == RefundStatus.UNKNOWN ? 0 : statusCheckCount));
            if (nextStatusCheckAt == null || nextStatusCheckAt.isAfter(soonest)) {
                nextStatusCheckAt = soonest;
            }
        }
        updatedAt = now;
        changes.add(new StatusChange(StatusChange.Entity.REFUND, id, before.name(), target.name(), source,
                newFailure == null ? null : newFailure.code(), now));
        return TransitionOutcome.APPLIED;
    }

    public void afterStatusCheck(Instant now) {
        statusCheckCount++;
        if (status.isFinal()) {
            nextStatusCheckAt = null;
        } else if (StatusCheckSchedule.exhausted(createdAt, now)) {
            nextStatusCheckAt = null;
            review = review.flag(Review.STATUS_UNRESOLVED, now);
        } else {
            nextStatusCheckAt = now.plus(StatusCheckSchedule.delay(statusCheckCount));
        }
        updatedAt = now;
    }

    /** Acknowledges the review (ADR-016); the refund's money state is untouched. */
    public void resolveReview(Instant now) {
        if (!review.open()) {
            throw com.payments.gateway.shared.error.GatewayException.invalidState("Refund is not awaiting review");
        }
        review = review.resolve();
        updatedAt = now;
    }

    public void flagForReview(String reason, Instant now) {
        review = review.flag(reason, now);
        updatedAt = now;
    }

    /** A return of a bank transfer credit, which is not a refund of the payment's captured amount (ADR-038). */
    public boolean returnsCredit() {
        return initiatedBy == RefundInitiator.SYSTEM_CREDIT_RETURN;
    }

    public String creditId() {
        return creditId;
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

    public Money amount() {
        return amount;
    }

    public String reason() {
        return reason;
    }

    public String merchantRefundId() {
        return merchantRefundId;
    }

    public RefundInitiator initiatedBy() {
        return initiatedBy;
    }

    public RefundStatus status() {
        return status;
    }

    public String providerReference() {
        return providerReference;
    }

    public Failure failure() {
        return failure;
    }

    public boolean needsReview() {
        return review.open();
    }

    public Review review() {
        return review;
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

    public boolean isNew() {
        return isNew;
    }
}
