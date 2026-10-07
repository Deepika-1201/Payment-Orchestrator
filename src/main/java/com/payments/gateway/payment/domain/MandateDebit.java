package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Schedules one debit of a mandate (LLD §18.1, §18.5): notification, then execution as an attempt of its payment.
 * Each execution cycle (the first and every retry) has its own notification. Mutated under the payment row lock,
 * which callers take before the debit row (lock order mandate → payment → debit).
 */
public final class MandateDebit {

    private final String id;
    private final String mandateId;
    private final String merchantId;
    private final String paymentId;
    private final String merchantDebitId;
    private final Money amount;
    private final long maxAmount;
    private final Long frictionlessLimit;
    private final boolean requiresNotification;
    private final String description;
    private final Instant dueAt;
    private final Instant createdAt;
    private MandateDebitStatus status;
    private Instant notBefore;
    private int cycle;
    private String notificationReference;
    private Instant notificationRequestedAt;
    private Instant notifiedAt;
    private Instant nextActionAt;
    private int checkCount;
    private Instant lastExecutedAt;
    private String failureCode;
    private String failureMessage;
    private long version;
    private Instant updatedAt;
    private boolean isNew;
    private boolean dirty;
    private final List<StatusChange> changes = new ArrayList<>();

    private MandateDebit(MandateDebitSnapshot s, boolean isNew) {
        this.id = s.id();
        this.mandateId = s.mandateId();
        this.merchantId = s.merchantId();
        this.paymentId = s.paymentId();
        this.merchantDebitId = s.merchantDebitId();
        this.amount = s.amount();
        this.maxAmount = s.maxAmount();
        this.frictionlessLimit = s.frictionlessLimit();
        this.requiresNotification = s.requiresNotification();
        this.description = s.description();
        this.dueAt = s.dueAt();
        this.createdAt = s.createdAt();
        this.status = s.status();
        this.notBefore = s.notBefore();
        this.cycle = s.cycle();
        this.notificationReference = s.notificationReference();
        this.notificationRequestedAt = s.notificationRequestedAt();
        this.notifiedAt = s.notifiedAt();
        this.nextActionAt = s.nextActionAt();
        this.checkCount = s.checkCount();
        this.lastExecutedAt = s.lastExecutedAt();
        this.failureCode = s.failureCode();
        this.failureMessage = s.failureMessage();
        this.version = s.version();
        this.updatedAt = s.updatedAt();
        this.isNew = isNew;
    }

    static MandateDebit schedule(String id, String mandateId, String merchantId, String paymentId, String merchantDebitId,
                                 Money amount, long maxAmount, Long frictionlessLimit, boolean requiresNotification,
                                 String description, Instant dueAt, Duration notifyAhead, Instant now) {
        MandateDebitStatus initial = requiresNotification ? MandateDebitStatus.SCHEDULED : MandateDebitStatus.READY;
        MandateDebit debit = new MandateDebit(new MandateDebitSnapshot(id, mandateId, merchantId, paymentId,
                merchantDebitId, amount, maxAmount, frictionlessLimit, requiresNotification, initial, description, dueAt,
                dueAt, 1, null, null, null, firstAction(requiresNotification, dueAt, notifyAhead, now), 0, null, null, null,
                0, now, now), true);
        debit.record(null, initial, TransitionSource.API, "created", now);
        return debit;
    }

    public static MandateDebit rehydrate(MandateDebitSnapshot snapshot) {
        return new MandateDebit(snapshot, false);
    }

    public MandateDebitSnapshot snapshot() {
        return new MandateDebitSnapshot(id, mandateId, merchantId, paymentId, merchantDebitId, amount, maxAmount,
                frictionlessLimit, requiresNotification, status, description, dueAt, notBefore, cycle,
                notificationReference, notificationRequestedAt, notifiedAt, nextActionAt, checkCount, lastExecutedAt,
                failureCode, failureMessage, version, createdAt, updatedAt);
    }

    /** One notification per cycle, so a retried request after a timeout finds the same notification at the PSP. */
    public String notificationId() {
        return id + "." + cycle;
    }

    // ---------------------------------------------------------------- commands

    public void notificationRequested(String reference, Duration checkInterval, Instant now) {
        require(MandateDebitStatus.SCHEDULED);
        notificationReference = reference;
        notificationRequestedAt = now;
        checkCount = 0;
        nextActionAt = now.plus(checkInterval);
        transition(MandateDebitStatus.NOTIFYING, TransitionSource.PROVIDER_RESPONSE, null, now);
    }

    /** The PSP delivered the cycle's notification: execution waits until {@code lead} after delivery. */
    public boolean notificationDelivered(String reference, Instant deliveredAt, Duration lead, TransitionSource source,
                                         Instant now) {
        if (status != MandateDebitStatus.SCHEDULED && status != MandateDebitStatus.NOTIFYING) {
            return false;
        }
        if (notificationReference == null) {
            notificationReference = reference;
        }
        notifiedAt = deliveredAt;
        Instant earliest = deliveredAt.plus(lead);
        nextActionAt = earliest.isAfter(notBefore) ? earliest : notBefore;
        transition(MandateDebitStatus.READY, source, null, now);
        return true;
    }

    /** The notification is not delivered yet, or the PSP could not be asked: look again later. */
    public void checkAgainAt(Instant next, Instant now) {
        checkCount++;
        nextActionAt = next;
        markDirty(now);
    }

    /** Gives up on notifying a cycle once it is this far past the cycle's earliest execution time. */
    public boolean notificationOverdue(Duration timeout, Instant now) {
        return (status == MandateDebitStatus.SCHEDULED || status == MandateDebitStatus.NOTIFYING)
                && !now.isBefore(notBefore.plus(timeout));
    }

    public void executionStarted(Instant now) {
        require(MandateDebitStatus.READY);
        lastExecutedAt = now;
        nextActionAt = null;
        transition(MandateDebitStatus.EXECUTING, TransitionSource.SYSTEM, "cycle " + cycle, now);
    }

    public boolean fail(String code, String message, TransitionSource source, Instant now) {
        if (status.isFinal()) {
            return false;
        }
        failureCode = code;
        failureMessage = message;
        nextActionAt = null;
        transition(MandateDebitStatus.FAILED, source, code, now);
        return true;
    }

    /** Cancels a debit that has not started executing; returns false otherwise. */
    public boolean cancel(TransitionSource source, String reason, Instant now) {
        if (!status.isWaiting()) {
            return false;
        }
        nextActionAt = null;
        transition(MandateDebitStatus.CANCELLED, source, reason, now);
        return true;
    }

    /**
     * Follows the debit's payment (same transaction as the payment's save). A failed attempt that leaves the payment
     * open starts the next cycle: a new notification for card and UPI, then execution no earlier than
     * {@code retryInterval} from now.
     */
    public boolean followPayment(PaymentStatus paymentStatus, String paymentFailureCode, String paymentFailureMessage,
                                 Duration retryInterval, Duration notifyAhead, Instant now) {
        if (status.isFinal()) {
            return false;
        }
        switch (paymentStatus) {
            case SUCCEEDED -> {
                nextActionAt = null;
                transition(MandateDebitStatus.SUCCEEDED, TransitionSource.SYSTEM, "payment succeeded", now);
                return true;
            }
            case FAILED -> {
                return fail(paymentFailureCode == null ? "payment_failed" : paymentFailureCode, paymentFailureMessage,
                        TransitionSource.SYSTEM, now);
            }
            case EXPIRED -> {
                return fail("payment_expired", "The debit's payment expired before it succeeded", TransitionSource.SYSTEM, now);
            }
            case CANCELLED -> {
                nextActionAt = null;
                transition(MandateDebitStatus.CANCELLED, TransitionSource.SYSTEM, "payment cancelled", now);
                return true;
            }
            case REQUIRES_PAYMENT_METHOD -> {
                if (status != MandateDebitStatus.EXECUTING) {
                    return false;
                }
                cycle++;
                notBefore = now.plus(retryInterval);
                notificationReference = null;
                notificationRequestedAt = null;
                notifiedAt = null;
                lastExecutedAt = null;
                checkCount = 0;
                nextActionAt = firstAction(requiresNotification, notBefore, notifyAhead, now);
                transition(requiresNotification ? MandateDebitStatus.SCHEDULED : MandateDebitStatus.READY,
                        TransitionSource.SYSTEM, "retry, cycle " + cycle, now);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static Instant firstAction(boolean requiresNotification, Instant notBefore, Duration notifyAhead, Instant now) {
        Instant at = requiresNotification ? notBefore.minus(notifyAhead) : notBefore;
        return at.isAfter(now) ? at : now;
    }

    private void require(MandateDebitStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("debit " + id + " is " + status + ", expected " + expected);
        }
    }

    private void transition(MandateDebitStatus target, TransitionSource source, String reason, Instant now) {
        MandateDebitStatus before = status;
        status = target;
        record(before, target, source, reason, now);
        markDirty(now);
    }

    private void record(MandateDebitStatus from, MandateDebitStatus to, TransitionSource source, String reason,
                        Instant now) {
        changes.add(new StatusChange(StatusChange.Entity.DEBIT, id, from == null ? null : from.name(), to.name(), source,
                reason, now));
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
    }

    public List<StatusChange> pullChanges() {
        List<StatusChange> pulled = List.copyOf(changes);
        changes.clear();
        return pulled;
    }

    // ---------------------------------------------------------------- queries

    public String id() {
        return id;
    }

    public String mandateId() {
        return mandateId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String paymentId() {
        return paymentId;
    }

    public String merchantDebitId() {
        return merchantDebitId;
    }

    public Money amount() {
        return amount;
    }

    public boolean requiresNotification() {
        return requiresNotification;
    }

    public MandateDebitStatus status() {
        return status;
    }

    public String description() {
        return description;
    }

    public Instant dueAt() {
        return dueAt;
    }

    public Instant notBefore() {
        return notBefore;
    }

    public int cycle() {
        return cycle;
    }

    public String notificationReference() {
        return notificationReference;
    }

    public Instant notifiedAt() {
        return notifiedAt;
    }

    public Instant nextActionAt() {
        return nextActionAt;
    }

    public Instant lastExecutedAt() {
        return lastExecutedAt;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
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

    public boolean isDirty() {
        return dirty;
    }
}
