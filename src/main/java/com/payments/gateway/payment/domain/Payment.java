package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Aggregate root for a merchant's payment intent and its attempts (ADR-007). Callers must hold the payment row
 * lock while mutating; every change is recorded as a {@link StatusChange} and merchant-visible changes emit a
 * {@link PaymentEvent}.
 */
public final class Payment {

    private final String id;
    private final String merchantId;
    private final String merchantOrderId;
    private final Money amount;
    private final CaptureMethod captureMethod;
    private final String description;
    private final Customer customer;
    private final Map<String, String> metadata;
    private final Instant expiresAt;
    private final Instant createdAt;
    private final String mandateId;
    private final Integer attemptLimit;
    private PaymentStatus status;
    private long amountCaptured;
    private long amountRefunded;
    private String succeededAttemptId;
    private String cancellationReason;
    private String failureCode;
    private String failureMessage;
    private Instant authorizationExpiresAt;
    private Instant updatedAt;
    private long version;
    private final List<PaymentAttempt> attempts;
    private final List<StatusChange> changes = new ArrayList<>();
    private final List<PaymentEvent> events = new ArrayList<>();
    private boolean isNew;
    private boolean dirty;

    private Payment(PaymentSnapshot s, List<PaymentAttempt> attempts, boolean isNew) {
        this.id = s.id();
        this.merchantId = s.merchantId();
        this.merchantOrderId = s.merchantOrderId();
        this.amount = s.amount();
        this.captureMethod = s.captureMethod();
        this.description = s.description();
        this.customer = s.customer() == null ? Customer.NONE : s.customer();
        this.metadata = s.metadata() == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(s.metadata()));
        this.expiresAt = s.expiresAt();
        this.createdAt = s.createdAt();
        this.mandateId = s.mandateId();
        this.attemptLimit = s.attemptLimit();
        this.status = s.status();
        this.amountCaptured = s.amountCaptured();
        this.amountRefunded = s.amountRefunded();
        this.succeededAttemptId = s.succeededAttemptId();
        this.cancellationReason = s.cancellationReason();
        this.failureCode = s.failureCode();
        this.failureMessage = s.failureMessage();
        this.authorizationExpiresAt = s.authorizationExpiresAt();
        this.updatedAt = s.updatedAt();
        this.version = s.version();
        this.attempts = new ArrayList<>(attempts);
        this.isNew = isNew;
    }

    public static Payment create(String id, String merchantId, String merchantOrderId, Money amount,
                                 CaptureMethod captureMethod, String description, Customer customer,
                                 Map<String, String> metadata, Instant expiresAt, Instant now) {
        Payment payment = new Payment(new PaymentSnapshot(id, merchantId, merchantOrderId, amount,
                PaymentStatus.REQUIRES_PAYMENT_METHOD, captureMethod, description, customer, metadata, 0, 0, null, null,
                null, null, expiresAt, null, 0, now, now, null, null), List.of(), true);
        payment.record(StatusChange.Entity.PAYMENT, id, null, PaymentStatus.REQUIRES_PAYMENT_METHOD.name(),
                TransitionSource.API, "created", now);
        return payment;
    }

    /** A mandate's registration charge or debit: captured automatically, attempts started only by the gateway. */
    public static Payment createForMandate(String id, String merchantId, String merchantOrderId, Money amount,
                                           String description, Customer customer, Map<String, String> metadata,
                                           Instant expiresAt, String mandateId, int attemptLimit, Instant now) {
        Payment payment = new Payment(new PaymentSnapshot(id, merchantId, merchantOrderId, amount,
                PaymentStatus.REQUIRES_PAYMENT_METHOD, CaptureMethod.AUTOMATIC, description, customer, metadata, 0, 0, null,
                null, null, null, expiresAt, null, 0, now, now, mandateId, attemptLimit), List.of(), true);
        payment.record(StatusChange.Entity.PAYMENT, id, null, PaymentStatus.REQUIRES_PAYMENT_METHOD.name(),
                TransitionSource.API, "created for mandate " + mandateId, now);
        return payment;
    }

    public static Payment rehydrate(PaymentSnapshot snapshot, List<PaymentAttempt> attempts) {
        return new Payment(snapshot, attempts, false);
    }

    public PaymentSnapshot snapshot() {
        return new PaymentSnapshot(id, merchantId, merchantOrderId, amount, status, captureMethod, description, customer,
                metadata, amountCaptured, amountRefunded, succeededAttemptId, cancellationReason, failureCode,
                failureMessage, expiresAt, authorizationExpiresAt, version, createdAt, updatedAt, mandateId, attemptLimit);
    }

    // ---------------------------------------------------------------- commands

    public void ensureConfirmable(int maxAttempts, Instant now) {
        if (status != PaymentStatus.REQUIRES_PAYMENT_METHOD) {
            throw GatewayException.invalidState("Cannot confirm a payment in status " + status);
        }
        if (!now.isBefore(expiresAt)) {
            throw GatewayException.invalidState("Payment has expired");
        }
        if (attempts.size() >= maxAttempts) {
            throw GatewayException.invalidState("Maximum number of attempts (" + maxAttempts + ") reached");
        }
    }

    public PaymentAttempt startAttempt(String attemptId, PaymentMethod method, String providerCode,
                                       String routingRuleId, int maxAttempts, Instant now) {
        ensureConfirmable(maxAttempts, now);
        PaymentAttempt attempt = PaymentAttempt.initiate(attemptId, id, merchantId, attempts.size() + 1, providerCode,
                method, amount, routingRuleId, now);
        attempts.add(attempt);
        record(StatusChange.Entity.ATTEMPT, attemptId, null, AttemptStatus.INITIATED.name(), TransitionSource.API,
                "routed to " + providerCode, now);
        failureCode = null;
        failureMessage = null;
        changeStatus(PaymentStatus.PROCESSING, TransitionSource.API, "attempt started", now);
        return attempt;
    }

    /** Replaces an attempt whose PSP definitely did not process it with a new attempt on the next candidate. */
    public PaymentAttempt failover(String failedAttemptId, Failure failure, String newAttemptId, String newProviderCode,
                                   Instant now) {
        PaymentAttempt failed = requireAttempt(failedAttemptId);
        if (failed.status() != AttemptStatus.INITIATED || failed != latestAttempt()) {
            throw new IllegalStateException("failover requires the latest attempt in INITIATED");
        }
        failed.apply(new AttemptUpdate(AttemptStatus.FAILED, null, null, failure, null), TransitionSource.PROVIDER_RESPONSE, now);
        record(StatusChange.Entity.ATTEMPT, failedAttemptId, AttemptStatus.INITIATED.name(), AttemptStatus.FAILED.name(),
                TransitionSource.PROVIDER_RESPONSE, failure.code(), now);
        PaymentAttempt next = PaymentAttempt.initiate(newAttemptId, id, merchantId, attempts.size() + 1, newProviderCode,
                failed.method(), amount, failed.routingRuleId(), now);
        if (failed.risk() != null) {
            next.recordRisk(failed.risk(), now);
        }
        if (failed.needsReview() && failed.review().reasonList().contains(Review.RISK_REVIEW)) {
            next.flagForReview(Review.RISK_REVIEW, now);
            if (failed.review().reasonList().size() == 1) {
                failed.resolveReview(now); // the risk review follows the payment to the live attempt
            }
        }
        attempts.add(next);
        record(StatusChange.Entity.ATTEMPT, newAttemptId, null, AttemptStatus.INITIATED.name(), TransitionSource.SYSTEM,
                "failover from " + failed.providerCode(), now);
        markDirty(now);
        return next;
    }

    public AttemptApplyResult applyAttemptUpdate(String attemptId, AttemptUpdate update, TransitionSource source,
                                                 PaymentPolicy policy, Instant now) {
        PaymentAttempt attempt = requireAttempt(attemptId);
        boolean movesMoney = update.status() == AttemptStatus.SUCCEEDED || update.status() == AttemptStatus.AUTHORIZED;
        if (movesMoney && update.reportedAmount() != null && !update.reportedAmount().equals(attempt.amount())) {
            attempt.flagForReview(Review.AMOUNT_MISMATCH, now);
            markDirty(now);
            return AttemptApplyResult.amountMismatch(attemptId);
        }
        AttemptStatus before = attempt.status();
        TransitionOutcome outcome = attempt.apply(update, source, now);
        if (attempt.isDirty()) {
            markDirty(now);
        }
        if (outcome == TransitionOutcome.CONFLICT) {
            attempt.flagForReview(Review.PROVIDER_CONFLICT, now);
            markDirty(now);
            return AttemptApplyResult.conflict(attemptId);
        }
        if (outcome == TransitionOutcome.NO_OP) {
            return AttemptApplyResult.noOp(attemptId);
        }
        record(StatusChange.Entity.ATTEMPT, attemptId, before.name(), attempt.status().name(), source,
                attempt.failure() == null ? null : attempt.failure().code(), now);
        return switch (attempt.status()) {
            case SUCCEEDED -> onAttemptSucceeded(attempt, source, policy, now);
            case AUTHORIZED -> onAttemptAuthorized(attempt, source, policy, now);
            case FAILED -> onAttemptFailed(attempt, source, policy, now);
            case VOIDED -> onAttemptVoided(attempt, source, now);
            case REQUIRES_ACTION -> {
                if (attempt == latestAttempt() && status == PaymentStatus.PROCESSING) {
                    changeStatus(PaymentStatus.REQUIRES_ACTION, source, "customer action required", now);
                }
                yield AttemptApplyResult.applied(attemptId);
            }
            case PENDING, UNKNOWN -> {
                if (attempt == latestAttempt() && status == PaymentStatus.REQUIRES_ACTION) {
                    changeStatus(PaymentStatus.PROCESSING, source, "awaiting provider", now);
                }
                yield AttemptApplyResult.applied(attemptId);
            }
            default -> AttemptApplyResult.applied(attemptId);
        };
    }

    public void blockByRisk(List<String> reasons, Instant now) {
        if (status != PaymentStatus.REQUIRES_PAYMENT_METHOD) {
            throw GatewayException.invalidState("Cannot block a payment in status " + status);
        }
        failureCode = "risk_blocked";
        failureMessage = "Blocked by risk rules: " + String.join(", ", reasons);
        changeStatus(PaymentStatus.FAILED, TransitionSource.SYSTEM, failureMessage, now);
        events.add(new PaymentEvent(PaymentEvent.Type.PAYMENT_FAILED, id));
    }

    /** Starts the gateway's attempt on a mandate payment; {@code providerReference} is known up front (LLD §18.3). */
    public PaymentAttempt startMandateAttempt(String attemptId, String providerCode, String providerReference,
                                              Instant now) {
        if (mandateId == null) {
            throw new IllegalStateException("payment " + id + " is not a mandate payment");
        }
        ensureConfirmable(attemptLimit, now);
        PaymentAttempt attempt = PaymentAttempt.initiate(attemptId, id, merchantId, attempts.size() + 1, providerCode,
                PaymentMethod.mandate(mandateId), amount, null, providerReference, now);
        attempts.add(attempt);
        record(StatusChange.Entity.ATTEMPT, attemptId, null, AttemptStatus.INITIATED.name(), TransitionSource.SYSTEM,
                "mandate " + mandateId, now);
        failureCode = null;
        failureMessage = null;
        changeStatus(PaymentStatus.PROCESSING, TransitionSource.SYSTEM, "attempt started", now);
        return attempt;
    }

    /** Ends a payment that has no attempt in flight, e.g. a debit whose notification or mandate failed. */
    public void fail(String code, String message, TransitionSource source, Instant now) {
        if (status != PaymentStatus.REQUIRES_PAYMENT_METHOD) {
            throw GatewayException.invalidState("Cannot fail a payment in status " + status);
        }
        failureCode = code;
        failureMessage = message;
        changeStatus(PaymentStatus.FAILED, source, code, now);
        events.add(new PaymentEvent(PaymentEvent.Type.PAYMENT_FAILED, id));
    }

    public PaymentAttempt requestCapture(Long captureAmount, Instant now) {
        if (status != PaymentStatus.AUTHORIZED) {
            throw GatewayException.invalidState("Cannot capture a payment in status " + status);
        }
        if (captureAmount != null && captureAmount != amount.amount()) {
            throw new GatewayException(ErrorCode.CAPTURE_AMOUNT_MISMATCH,
                    "Partial capture is not supported; capture amount must be " + amount.amount());
        }
        PaymentAttempt attempt = authorizedAttempt();
        attempt.requestCapture(now);
        record(StatusChange.Entity.ATTEMPT, attempt.id(), AttemptStatus.AUTHORIZED.name(),
                AttemptStatus.CAPTURE_PENDING.name(), TransitionSource.API, "capture requested", now);
        changeStatus(PaymentStatus.PROCESSING, TransitionSource.API, "capture requested", now);
        return attempt;
    }

    public void rejectCapture(String attemptId, Failure failure, Instant now) {
        PaymentAttempt attempt = requireAttempt(attemptId);
        if (attempt.status() != AttemptStatus.CAPTURE_PENDING) {
            return;
        }
        attempt.rejectCapture(failure, now);
        record(StatusChange.Entity.ATTEMPT, attemptId, AttemptStatus.CAPTURE_PENDING.name(),
                AttemptStatus.AUTHORIZED.name(), TransitionSource.PROVIDER_RESPONSE, failure.code(), now);
        if (status == PaymentStatus.PROCESSING) {
            failureCode = failure.code();
            failureMessage = failure.message();
            changeStatus(PaymentStatus.AUTHORIZED, TransitionSource.PROVIDER_RESPONSE, "capture rejected", now);
        }
    }

    /** Cancels the payment; returns the attempt whose authorization must be voided at the PSP, if any. */
    public Optional<String> cancel(String reason, Instant now) {
        String voidAttemptId = null;
        switch (status) {
            case REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION -> {
            }
            case AUTHORIZED -> {
                PaymentAttempt attempt = authorizedAttempt();
                attempt.requestVoid(now);
                voidAttemptId = attempt.id();
            }
            default -> throw GatewayException.invalidState("Cannot cancel a payment in status " + status);
        }
        cancellationReason = reason == null ? "requested_by_customer" : reason;
        changeStatus(PaymentStatus.CANCELLED, TransitionSource.API, cancellationReason, now);
        events.add(new PaymentEvent(PaymentEvent.Type.PAYMENT_CANCELLED, id));
        return Optional.ofNullable(voidAttemptId);
    }

    public record ExpireResult(boolean changed, String voidAttemptId) {
    }

    public ExpireResult expire(Instant now, Duration processingGrace) {
        PaymentAttempt latest = latestAttempt();
        boolean due = switch (status) {
            case REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION -> !now.isBefore(expiresAt);
            case PROCESSING -> !now.isBefore(expiresAt.plus(processingGrace))
                    && (latest == null || latest.status() != AttemptStatus.CAPTURE_PENDING);
            case AUTHORIZED -> authorizationExpiresAt != null && !now.isBefore(authorizationExpiresAt);
            default -> false;
        };
        if (!due) {
            return new ExpireResult(false, null);
        }
        String voidAttemptId = null;
        String reason = "payment expired";
        if (status == PaymentStatus.AUTHORIZED) {
            PaymentAttempt attempt = authorizedAttempt();
            attempt.requestVoid(now);
            voidAttemptId = attempt.id();
            reason = "authorization lapsed";
        }
        changeStatus(PaymentStatus.EXPIRED, TransitionSource.SYSTEM, reason, now);
        events.add(new PaymentEvent(PaymentEvent.Type.PAYMENT_EXPIRED, id));
        return new ExpireResult(true, voidAttemptId);
    }

    public void recordRefundSucceeded(String attemptId, long refundAmount, Instant now) {
        if (!attemptId.equals(succeededAttemptId)) {
            markDirty(now);
            return;
        }
        long updated = Math.addExact(amountRefunded, refundAmount);
        if (updated > amountCaptured) {
            throw new IllegalStateException("refunds would exceed the captured amount of payment " + id);
        }
        amountRefunded = updated;
        markDirty(now);
    }

    public void recordStatusCheck(String attemptId, Instant now) {
        requireAttempt(attemptId).afterStatusCheck(now);
        markDirty(now);
    }

    /** Stores the risk decision on the attempt; a proceeding non-ALLOW outcome queues it for a human. */
    public void recordRisk(String attemptId, RiskAssessment assessment, Instant now) {
        PaymentAttempt attempt = requireAttempt(attemptId);
        attempt.recordRisk(assessment, now);
        if (!"ALLOW".equals(assessment.outcome())) {
            attempt.flagForReview(Review.RISK_REVIEW, now);
        }
        markDirty(now);
    }

    /** Acknowledges an attempt's review (ADR-016); money state is untouched. */
    public PaymentAttempt resolveAttemptReview(String attemptId, Instant now) {
        PaymentAttempt attempt = requireAttempt(attemptId);
        if (!attempt.needsReview()) {
            throw GatewayException.invalidState("Attempt is not awaiting review");
        }
        attempt.resolveReview(now);
        markDirty(now);
        return attempt;
    }

    /** Bumps the version so concurrent writers serialize on this payment (e.g. refund creation). */
    public void touch(Instant now) {
        markDirty(now);
    }

    // ---------------------------------------------------------------- transition handlers

    private AttemptApplyResult onAttemptSucceeded(PaymentAttempt attempt, TransitionSource source, PaymentPolicy policy,
                                                  Instant now) {
        if (status == PaymentStatus.SUCCEEDED) {
            return attempt.id().equals(succeededAttemptId)
                    ? AttemptApplyResult.applied(attempt.id())
                    : AttemptApplyResult.refund(attempt.id(), RefundInitiator.SYSTEM_DUPLICATE_SUCCESS);
        }
        if (!status.isTerminal()) {
            markSucceeded(attempt, source, "attempt succeeded", now);
            return AttemptApplyResult.applied(attempt.id());
        }
        if (status == PaymentStatus.CANCELLED || !policy.acceptLateSuccess()) {
            return AttemptApplyResult.refund(attempt.id(), RefundInitiator.SYSTEM_LATE_SUCCESS);
        }
        markSucceeded(attempt, source, "late success accepted", now);
        return AttemptApplyResult.lateSuccess(attempt.id());
    }

    private AttemptApplyResult onAttemptAuthorized(PaymentAttempt attempt, TransitionSource source, PaymentPolicy policy,
                                                   Instant now) {
        if (status.isTerminal() || status == PaymentStatus.AUTHORIZED) {
            attempt.requestVoid(now);
            return AttemptApplyResult.voidAuthorization(attempt.id());
        }
        authorizationExpiresAt = now.plus(policy.authorizationTtl());
        failureCode = null;
        failureMessage = null;
        changeStatus(PaymentStatus.AUTHORIZED, source, "attempt authorized", now);
        if (captureMethod == CaptureMethod.AUTOMATIC) {
            attempt.requestCapture(now);
            record(StatusChange.Entity.ATTEMPT, attempt.id(), AttemptStatus.AUTHORIZED.name(),
                    AttemptStatus.CAPTURE_PENDING.name(), TransitionSource.SYSTEM, "automatic capture", now);
            changeStatus(PaymentStatus.PROCESSING, TransitionSource.SYSTEM, "automatic capture", now);
            return AttemptApplyResult.capture(attempt.id());
        }
        events.add(new PaymentEvent(PaymentEvent.Type.PAYMENT_AUTHORIZED, id));
        return AttemptApplyResult.applied(attempt.id());
    }

    private AttemptApplyResult onAttemptFailed(PaymentAttempt attempt, TransitionSource source, PaymentPolicy policy,
                                               Instant now) {
        boolean waitingOnThisAttempt = attempt == latestAttempt()
                && (status == PaymentStatus.PROCESSING || status == PaymentStatus.REQUIRES_ACTION);
        if (!waitingOnThisAttempt) {
            return AttemptApplyResult.applied(attempt.id());
        }
        failureCode = attempt.failure().code();
        failureMessage = attempt.failure().message();
        if (attempts.size() < policy.maxAttempts() && now.isBefore(expiresAt)) {
            changeStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD, source, "attempt failed", now);
            events.add(new PaymentEvent(PaymentEvent.Type.PAYMENT_ATTEMPT_FAILED, id));
        } else {
            changeStatus(PaymentStatus.FAILED, source, "attempt failed, no retries left", now);
            events.add(new PaymentEvent(PaymentEvent.Type.PAYMENT_FAILED, id));
        }
        return AttemptApplyResult.applied(attempt.id());
    }

    private AttemptApplyResult onAttemptVoided(PaymentAttempt attempt, TransitionSource source, Instant now) {
        if (status == PaymentStatus.AUTHORIZED && attempt == latestAttempt()) {
            changeStatus(PaymentStatus.EXPIRED, source, "authorization voided by provider", now);
            events.add(new PaymentEvent(PaymentEvent.Type.PAYMENT_EXPIRED, id));
        }
        return AttemptApplyResult.applied(attempt.id());
    }

    private void markSucceeded(PaymentAttempt attempt, TransitionSource source, String reason, Instant now) {
        amountCaptured = attempt.amount().amount();
        succeededAttemptId = attempt.id();
        failureCode = null;
        failureMessage = null;
        authorizationExpiresAt = null;
        for (PaymentAttempt other : attempts) {
            if (other != attempt && other.status() == AttemptStatus.AUTHORIZED) {
                other.requestVoid(now);
            }
        }
        changeStatus(PaymentStatus.SUCCEEDED, source, reason, now);
        events.add(new PaymentEvent(PaymentEvent.Type.PAYMENT_SUCCEEDED, id));
    }

    // ---------------------------------------------------------------- helpers

    private PaymentAttempt authorizedAttempt() {
        return attempts.stream()
                .filter(a -> a.status() == AttemptStatus.AUTHORIZED)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("no authorized attempt on payment " + id));
    }

    private PaymentAttempt requireAttempt(String attemptId) {
        return attempt(attemptId).orElseThrow(() -> new IllegalArgumentException(
                "attempt " + attemptId + " does not belong to payment " + id));
    }

    private void changeStatus(PaymentStatus target, TransitionSource source, String reason, Instant now) {
        if (target == status) {
            return;
        }
        PaymentStatus before = status;
        status = target;
        record(StatusChange.Entity.PAYMENT, id, before.name(), target.name(), source, reason, now);
        markDirty(now);
    }

    private void record(StatusChange.Entity entity, String entityId, String from, String to, TransitionSource source,
                        String reason, Instant now) {
        changes.add(new StatusChange(entity, entityId, from, to, source, reason, now));
    }

    private void markDirty(Instant now) {
        updatedAt = now;
        dirty = true;
    }

    public void markPersisted() {
        if (!isNew) {
            version++;
        }
        isNew = false;
        dirty = false;
        attempts.forEach(PaymentAttempt::markPersisted);
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

    // ---------------------------------------------------------------- queries

    public Optional<PaymentAttempt> attempt(String attemptId) {
        return attempts.stream().filter(a -> a.id().equals(attemptId)).findFirst();
    }

    public PaymentAttempt latestAttempt() {
        return attempts.isEmpty() ? null : attempts.getLast();
    }

    public List<PaymentAttempt> attempts() {
        return Collections.unmodifiableList(attempts);
    }

    public String id() {
        return id;
    }

    public String merchantId() {
        return merchantId;
    }

    public String merchantOrderId() {
        return merchantOrderId;
    }

    public Money amount() {
        return amount;
    }

    public CaptureMethod captureMethod() {
        return captureMethod;
    }

    public String description() {
        return description;
    }

    public Customer customer() {
        return customer;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public String mandateId() {
        return mandateId;
    }

    /** The attempt limit of a mandate payment; null means the default policy applies. */
    public Integer attemptLimit() {
        return attemptLimit;
    }

    public PaymentStatus status() {
        return status;
    }

    public long amountCaptured() {
        return amountCaptured;
    }

    public long amountRefunded() {
        return amountRefunded;
    }

    public String succeededAttemptId() {
        return succeededAttemptId;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant authorizationExpiresAt() {
        return authorizationExpiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public boolean isNew() {
        return isNew;
    }

    public boolean isDirty() {
        return dirty || attempts.stream().anyMatch(PaymentAttempt::isDirty);
    }
}
