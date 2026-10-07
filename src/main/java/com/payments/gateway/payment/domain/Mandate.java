package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.MandateFrequency;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A customer's standing authorization for a merchant's recurring debits at one PSP (ADR-035, LLD §18). Callers hold
 * the mandate row lock while mutating; changes are recorded as {@link StatusChange}s and merchant-visible ones emit a
 * {@link PaymentEvent}.
 */
public final class Mandate {

    /** If the process dies before the PSP's answer to a registration is stored, the poller looks after this delay. */
    static final Duration CREATED_CHECK_DELAY = Duration.ofSeconds(30);
    private static final List<Duration> PENDING_CHECKS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofHours(1));

    private final String id;
    private final String merchantId;
    private final String providerCode;
    private final MandateInstrument instrument;
    private final Money maxAmount;
    private final MandateFrequency frequency;
    private final Instant startAt;
    private final Instant endAt;
    private final String description;
    private final MandateCustomer customer;
    private final Map<String, String> metadata;
    private final Instant authorizationExpiresAt;
    private final Instant createdAt;
    private MandateStatus status;
    private String registrationPaymentId;
    private String providerReference;
    private String providerMandateReference;
    private String providerCustomerReference;
    private NextAction nextAction;
    private Instant nextCheckAt;
    private int checkCount;
    private String failureCode;
    private String failureMessage;
    private Instant activatedAt;
    private long version;
    private Instant updatedAt;
    private boolean isNew;
    private boolean dirty;
    private final List<StatusChange> changes = new ArrayList<>();
    private final List<PaymentEvent> events = new ArrayList<>();

    private Mandate(MandateSnapshot s, boolean isNew) {
        this.id = s.id();
        this.merchantId = s.merchantId();
        this.providerCode = s.providerCode();
        this.instrument = s.instrument();
        this.maxAmount = s.maxAmount();
        this.frequency = s.frequency();
        this.startAt = s.startAt();
        this.endAt = s.endAt();
        this.description = s.description();
        this.customer = s.customer();
        this.metadata = s.metadata() == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(s.metadata()));
        this.authorizationExpiresAt = s.authorizationExpiresAt();
        this.createdAt = s.createdAt();
        this.status = s.status();
        this.registrationPaymentId = s.registrationPaymentId();
        this.providerReference = s.providerReference();
        this.providerMandateReference = s.providerMandateReference();
        this.providerCustomerReference = s.providerCustomerReference();
        this.nextAction = s.nextAction();
        this.nextCheckAt = s.nextCheckAt();
        this.checkCount = s.checkCount();
        this.failureCode = s.failureCode();
        this.failureMessage = s.failureMessage();
        this.activatedAt = s.activatedAt();
        this.version = s.version();
        this.updatedAt = s.updatedAt();
        this.isNew = isNew;
    }

    public static Mandate create(String id, String merchantId, String providerCode, MandateInstrument instrument,
                                 Money maxAmount, MandateFrequency frequency, Instant startAt, Instant endAt,
                                 String description, MandateCustomer customer, Map<String, String> metadata,
                                 Instant authorizationExpiresAt, Instant now) {
        if (!endAt.isAfter(startAt)) {
            throw GatewayException.validation("end_at", "must be after start_at");
        }
        Mandate mandate = new Mandate(new MandateSnapshot(id, merchantId, providerCode, instrument, MandateStatus.CREATED,
                maxAmount, frequency, startAt, endAt, description, customer, metadata, null, null, null, null, null,
                authorizationExpiresAt, now.plus(CREATED_CHECK_DELAY), 0, null, null, null, 0, now, now), true);
        mandate.record(null, MandateStatus.CREATED, TransitionSource.API, "created", now);
        return mandate;
    }

    public static Mandate rehydrate(MandateSnapshot snapshot) {
        return new Mandate(snapshot, false);
    }

    public MandateSnapshot snapshot() {
        return new MandateSnapshot(id, merchantId, providerCode, instrument, status, maxAmount, frequency, startAt, endAt,
                description, customer, metadata, registrationPaymentId, providerReference, providerMandateReference,
                providerCustomerReference, nextAction, authorizationExpiresAt, nextCheckAt, checkCount, failureCode,
                failureMessage, activatedAt, version, createdAt, updatedAt);
    }

    // ---------------------------------------------------------------- commands

    public void linkRegistrationPayment(String paymentId, Instant now) {
        registrationPaymentId = paymentId;
        markDirty(now);
    }

    /** Applies PSP evidence; returns whether the status changed. References are filled in even if it did not. */
    public boolean apply(MandateUpdate update, TransitionSource source, Instant now) {
        boolean dirtied = fillReferences(update);
        if (!status.isFinal() && status.rank() < MandateStatus.ACTIVE.rank()) {
            if (update.nextAction() != null) {
                nextAction = update.nextAction();
                dirtied = true;
            } else if (update.status() == MandateStatus.PENDING_AUTHORIZATION && nextAction != null) {
                // The customer has authorized; the PSP awaits the bank's confirmation (LLD §18.3).
                nextAction = null;
                dirtied = true;
            }
        }
        if (dirtied) {
            markDirty(now);
        }
        MandateStatus target = update.status();
        if (target == null || target == status || !status.canTransitionTo(target)) {
            return false;
        }
        MandateStatus before = status;
        switch (target) {
            case PENDING_AUTHORIZATION -> nextCheckAt = pendingCheckAfter(now);
            case ACTIVE -> {
                if (activatedAt == null) {
                    activatedAt = now;
                }
                nextAction = null;
                failureCode = null;
                failureMessage = null;
                nextCheckAt = endAt;
                events.add(new PaymentEvent(before == MandateStatus.PAUSED ? PaymentEvent.Type.MANDATE_RESUMED
                        : PaymentEvent.Type.MANDATE_ACTIVATED, id));
            }
            case PAUSED -> {
                nextAction = null;
                nextCheckAt = endAt;
                events.add(new PaymentEvent(PaymentEvent.Type.MANDATE_PAUSED, id));
            }
            case REVOKED -> end(PaymentEvent.Type.MANDATE_REVOKED);
            case EXPIRED -> end(PaymentEvent.Type.MANDATE_EXPIRED);
            case FAILED -> {
                Failure failure = update.failure();
                failureCode = failure == null ? "mandate_failed" : failure.code();
                failureMessage = failure == null ? null : failure.message();
                end(PaymentEvent.Type.MANDATE_FAILED);
            }
            default -> {
            }
        }
        status = target;
        record(before, target, source, failureCode != null && target == MandateStatus.FAILED ? failureCode : null, now);
        markDirty(now);
        return true;
    }

    /** Revocation by the merchant (API) or the gateway (SYSTEM); returns false if already final. */
    public boolean revoke(TransitionSource source, String reason, Instant now) {
        if (status.isFinal()) {
            return false;
        }
        MandateStatus before = status;
        end(PaymentEvent.Type.MANDATE_REVOKED);
        status = MandateStatus.REVOKED;
        record(before, MandateStatus.REVOKED, source, reason, now);
        markDirty(now);
        return true;
    }

    /** Ends an active or paused mandate at {@code end_at}; returns whether it expired now. */
    public boolean expireIfDue(Instant now) {
        if ((status != MandateStatus.ACTIVE && status != MandateStatus.PAUSED) || now.isBefore(endAt)) {
            return false;
        }
        return apply(MandateUpdate.of(MandateStatus.EXPIRED), TransitionSource.SYSTEM, now);
    }

    /** The window has passed while the customer still had to act: not yet registered, or a next action pending. */
    public boolean authorizationLapsed(Instant now) {
        boolean awaitingCustomer = status == MandateStatus.CREATED
                || (status == MandateStatus.PENDING_AUTHORIZATION && nextAction != null);
        return awaitingCustomer && !now.isBefore(authorizationExpiresAt);
    }

    /** Schedules the next poll: backoff while awaiting authorization (the last one at its deadline), else at end_at. */
    public void recordCheck(Instant now) {
        checkCount++;
        if (status.isFinal()) {
            nextCheckAt = null;
        } else if (status.rank() >= MandateStatus.ACTIVE.rank()) {
            nextCheckAt = endAt;
        } else {
            nextCheckAt = pendingCheckAfter(now);
        }
        markDirty(now);
    }

    /**
     * Validates and schedules a debit (FR-MD3, FR-MD4). {@code frictionlessLimit} applies to instruments whose debits
     * run without an additional factor of authentication (card and UPI).
     */
    public MandateDebit newDebit(String debitId, String paymentId, String merchantDebitId, Money amount,
                                 long frictionlessLimit, Instant dueAt, String debitDescription, Duration notifyAhead,
                                 Instant now) {
        if (status != MandateStatus.ACTIVE) {
            throw new GatewayException(ErrorCode.MANDATE_INVALID_STATE, "Cannot debit a mandate in status " + status);
        }
        if (!amount.currency().equals(maxAmount.currency())) {
            throw GatewayException.validation("currency", "must be the mandate's currency, " + maxAmount.currency());
        }
        if (amount.amount() > maxAmount.amount()) {
            throw new GatewayException(ErrorCode.AMOUNT_EXCEEDS_MANDATE_LIMIT,
                    "Amount exceeds the mandate's max_amount of " + maxAmount.amount());
        }
        Long limit = instrument.requiresDebitNotification() ? frictionlessLimit : null;
        if (limit != null && amount.amount() > limit) {
            throw new GatewayException(ErrorCode.AMOUNT_EXCEEDS_MANDATE_LIMIT, "Amount exceeds " + limit
                    + ", the limit for debits without the customer's additional authentication; collect a payment instead");
        }
        if (dueAt.isBefore(startAt) || !dueAt.isBefore(endAt)) {
            throw GatewayException.validation("due_at", "must be within the mandate's term (start_at to end_at)");
        }
        return MandateDebit.schedule(debitId, id, merchantId, paymentId, merchantDebitId, amount, maxAmount.amount(), limit,
                instrument.requiresDebitNotification(), debitDescription, dueAt, notifyAhead, now);
    }

    private void end(PaymentEvent.Type event) {
        nextAction = null;
        nextCheckAt = null;
        events.add(new PaymentEvent(event, id));
    }

    private boolean fillReferences(MandateUpdate update) {
        boolean changed = false;
        if (providerReference == null && update.providerReference() != null) {
            providerReference = update.providerReference();
            changed = true;
        }
        if (providerMandateReference == null && update.providerMandateReference() != null) {
            providerMandateReference = update.providerMandateReference();
            changed = true;
        }
        if (providerCustomerReference == null && update.providerCustomerReference() != null) {
            providerCustomerReference = update.providerCustomerReference();
            changed = true;
        }
        return changed;
    }

    private Instant pendingCheckAfter(Instant now) {
        Duration delay = PENDING_CHECKS.get(Math.min(checkCount, PENDING_CHECKS.size() - 1));
        Instant next = now.plus(delay);
        return next.isAfter(authorizationExpiresAt) && now.isBefore(authorizationExpiresAt) ? authorizationExpiresAt : next;
    }

    private void record(MandateStatus from, MandateStatus to, TransitionSource source, String reason, Instant now) {
        changes.add(new StatusChange(StatusChange.Entity.MANDATE, id, from == null ? null : from.name(), to.name(), source,
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

    public List<PaymentEvent> pullEvents() {
        List<PaymentEvent> pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    // ---------------------------------------------------------------- queries

    public String id() {
        return id;
    }

    public String merchantId() {
        return merchantId;
    }

    public String providerCode() {
        return providerCode;
    }

    public MandateInstrument instrument() {
        return instrument;
    }

    public MandateStatus status() {
        return status;
    }

    public Money maxAmount() {
        return maxAmount;
    }

    public MandateFrequency frequency() {
        return frequency;
    }

    public Instant startAt() {
        return startAt;
    }

    public Instant endAt() {
        return endAt;
    }

    public String description() {
        return description;
    }

    public MandateCustomer customer() {
        return customer;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public String registrationPaymentId() {
        return registrationPaymentId;
    }

    public String providerReference() {
        return providerReference;
    }

    public String providerMandateReference() {
        return providerMandateReference;
    }

    public String providerCustomerReference() {
        return providerCustomerReference;
    }

    public NextAction nextAction() {
        return nextAction;
    }

    public Instant authorizationExpiresAt() {
        return authorizationExpiresAt;
    }

    public Instant nextCheckAt() {
        return nextCheckAt;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public Instant activatedAt() {
        return activatedAt;
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
