package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.PaymentMethod;
import java.time.Duration;
import java.time.Instant;

/** One try of a payment at one PSP. Mutated only through its {@link Payment} aggregate root. */
public final class PaymentAttempt {

    /** Safety net: if the process dies before the PSP call completes, the resolver looks after this delay. */
    static final Duration INITIATED_CHECK_DELAY = Duration.ofSeconds(30);

    private final String id;
    private final String paymentId;
    private final String merchantId;
    private final int attemptNumber;
    private final String providerCode;
    private final PaymentMethod method;
    private final Money amount;
    private final String routingRuleId;
    private final Instant createdAt;
    private AttemptStatus status;
    private String providerReference;
    private NextAction nextAction;
    private Failure failure;
    private CardDetails card;
    private Instant authorizedAt;
    private Instant capturedAt;
    private Money captureAmount;
    private boolean voidRequested;
    private Instant nextStatusCheckAt;
    private int statusCheckCount;
    private Review review;
    private RiskAssessment risk;
    private long version;
    private Instant updatedAt;
    private boolean isNew;
    private boolean dirty;

    private PaymentAttempt(AttemptSnapshot s, boolean isNew) {
        this.id = s.id();
        this.paymentId = s.paymentId();
        this.merchantId = s.merchantId();
        this.attemptNumber = s.attemptNumber();
        this.providerCode = s.providerCode();
        this.method = s.method();
        this.amount = s.amount();
        this.routingRuleId = s.routingRuleId();
        this.createdAt = s.createdAt();
        this.status = s.status();
        this.providerReference = s.providerReference();
        this.nextAction = s.nextAction();
        this.failure = s.failure();
        this.card = s.card();
        this.authorizedAt = s.authorizedAt();
        this.capturedAt = s.capturedAt();
        this.captureAmount = s.captureAmount();
        this.voidRequested = s.voidRequested();
        this.nextStatusCheckAt = s.nextStatusCheckAt();
        this.statusCheckCount = s.statusCheckCount();
        this.review = s.review();
        this.risk = s.risk();
        this.version = s.version();
        this.updatedAt = s.updatedAt();
        this.isNew = isNew;
    }

    static PaymentAttempt initiate(String id, String paymentId, String merchantId, int attemptNumber, String providerCode,
                                   PaymentMethod method, Money amount, String routingRuleId, Instant now) {
        return initiate(id, paymentId, merchantId, attemptNumber, providerCode, method, amount, routingRuleId, null, now);
    }

    static PaymentAttempt initiate(String id, String paymentId, String merchantId, int attemptNumber, String providerCode,
                                   PaymentMethod method, Money amount, String routingRuleId, String providerReference,
                                   Instant now) {
        return new PaymentAttempt(new AttemptSnapshot(id, paymentId, merchantId, attemptNumber, providerCode, method,
                amount, AttemptStatus.INITIATED, providerReference, null, null, null, routingRuleId, null, null, null, false,
                now.plus(INITIATED_CHECK_DELAY), 0, Review.NONE, null, 0, now, now), true);
    }

    public static PaymentAttempt rehydrate(AttemptSnapshot snapshot) {
        return new PaymentAttempt(snapshot, false);
    }

    public AttemptSnapshot snapshot() {
        return new AttemptSnapshot(id, paymentId, merchantId, attemptNumber, providerCode, method, amount, status,
                providerReference, nextAction, failure, card, routingRuleId, authorizedAt, capturedAt, captureAmount,
                voidRequested, nextStatusCheckAt, statusCheckCount, review, risk, version, createdAt, updatedAt);
    }

    TransitionOutcome apply(AttemptUpdate update, TransitionSource source, Instant now) {
        if (update.providerReference() != null && providerReference == null) {
            providerReference = update.providerReference();
            touch(now);
        }
        if (update.card() != null && card == null) {
            card = update.card();
            touch(now);
        }
        AttemptStatus target = update.status();
        if (target == status) {
            return TransitionOutcome.NO_OP;
        }
        if (!status.canTransitionTo(target)) {
            boolean stale = target.rank() < status.rank() || (status.isFinal() && !target.isFinal());
            return stale ? TransitionOutcome.NO_OP : TransitionOutcome.CONFLICT;
        }
        if (status == AttemptStatus.FAILED && !source.isProviderEvidence()) {
            return TransitionOutcome.CONFLICT;
        }
        status = target;
        switch (target) {
            case REQUIRES_ACTION -> {
                if (update.nextAction() != null) {
                    nextAction = update.nextAction();
                }
            }
            case AUTHORIZED -> {
                authorizedAt = now;
                failure = null;
                nextAction = null;
            }
            case SUCCEEDED -> {
                if (captureAmount == null) {
                    captureAmount = amount; // captured by the PSP without a capture request: the full amount
                }
                capturedAt = now;
                failure = null;
                nextAction = null;
            }
            case FAILED -> {
                failure = update.failure();
                nextAction = null;
            }
            case VOIDED -> {
                voidRequested = false;
                nextAction = null;
            }
            case PENDING, UNKNOWN -> nextAction = null;
            default -> {
            }
        }
        reschedule(now);
        touch(now);
        return TransitionOutcome.APPLIED;
    }

    void requestCapture(Money requested, Instant now) {
        if (status != AttemptStatus.AUTHORIZED) {
            throw new IllegalStateException("capture requires AUTHORIZED, was " + status);
        }
        captureAmount = requested;
        status = AttemptStatus.CAPTURE_PENDING;
        nextStatusCheckAt = now.plus(Duration.ofSeconds(10));
        touch(now);
    }

    void rejectCapture(Failure rejection, Instant now) {
        if (status != AttemptStatus.CAPTURE_PENDING) {
            throw new IllegalStateException("capture rejection requires CAPTURE_PENDING, was " + status);
        }
        status = AttemptStatus.AUTHORIZED;
        captureAmount = null;
        failure = rejection;
        nextStatusCheckAt = null;
        touch(now);
    }

    void requestVoid(Instant now) {
        voidRequested = true;
        nextStatusCheckAt = now;
        touch(now);
    }

    /** Called after each status check: schedules the next one with backoff, or gives up and flags for review. */
    void afterStatusCheck(Instant now) {
        statusCheckCount++;
        if (!needsFollowUp()) {
            nextStatusCheckAt = null;
        } else if (StatusCheckSchedule.exhausted(createdAt, now)) {
            nextStatusCheckAt = null;
            review = review.flag(Review.STATUS_UNRESOLVED, now);
        } else {
            nextStatusCheckAt = now.plus(StatusCheckSchedule.delay(statusCheckCount));
        }
        touch(now);
    }

    void flagForReview(String reason, Instant now) {
        review = review.flag(reason, now);
        touch(now);
    }

    void resolveReview(Instant now) {
        review = review.resolve();
        touch(now);
    }

    void recordRisk(RiskAssessment assessment, Instant now) {
        risk = assessment;
        touch(now);
    }

    public boolean needsFollowUp() {
        return status.awaitsProvider() || (status == AttemptStatus.AUTHORIZED && voidRequested);
    }

    private void reschedule(Instant now) {
        if (!needsFollowUp()) {
            nextStatusCheckAt = null;
        } else if (status == AttemptStatus.UNKNOWN) {
            nextStatusCheckAt = now.plus(StatusCheckSchedule.delay(0));
        } else if (nextStatusCheckAt == null || nextStatusCheckAt.isAfter(now.plus(StatusCheckSchedule.delay(statusCheckCount)))) {
            nextStatusCheckAt = now.plus(StatusCheckSchedule.delay(statusCheckCount));
        }
    }

    private void touch(Instant now) {
        updatedAt = now;
        dirty = true;
    }

    void markPersisted() {
        if (!isNew) {
            version++;
        }
        isNew = false;
        dirty = false;
    }

    public String id() {
        return id;
    }

    public String paymentId() {
        return paymentId;
    }

    public String merchantId() {
        return merchantId;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public String providerCode() {
        return providerCode;
    }

    public PaymentMethod method() {
        return method;
    }

    public Money amount() {
        return amount;
    }

    /** The amount captured, or being captured, once a capture is requested or reported (ADR-036); else {@link #amount()}. */
    public Money capturedAmount() {
        return captureAmount == null ? amount : captureAmount;
    }

    public AttemptStatus status() {
        return status;
    }

    public String providerReference() {
        return providerReference;
    }

    public NextAction nextAction() {
        return nextAction;
    }

    public Failure failure() {
        return failure;
    }

    public CardDetails card() {
        return card;
    }

    public String routingRuleId() {
        return routingRuleId;
    }

    public boolean voidRequested() {
        return voidRequested;
    }

    public Instant nextStatusCheckAt() {
        return nextStatusCheckAt;
    }

    public boolean needsReview() {
        return review.open();
    }

    public Review review() {
        return review;
    }

    public RiskAssessment risk() {
        return risk;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isNew() {
        return isNew;
    }

    public boolean isDirty() {
        return dirty;
    }
}
