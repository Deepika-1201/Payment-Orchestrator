package com.payments.gateway.payment.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Attempt lifecycle. {@code rank} orders progress so that stale updates (lower rank) become no-ops instead of
 * regressions. {@code CAPTURE_PENDING} is entered only via capture requests and left backwards only via capture
 * rejection, never by generic PSP updates.
 */
public enum AttemptStatus {
    INITIATED(0),
    UNKNOWN(1),
    REQUIRES_ACTION(2),
    PENDING(3),
    AUTHORIZED(4),
    CAPTURE_PENDING(5),
    SUCCEEDED(6),
    FAILED(6),
    VOIDED(6);

    private static final Map<AttemptStatus, Set<AttemptStatus>> ALLOWED = new EnumMap<>(AttemptStatus.class);

    static {
        ALLOWED.put(INITIATED, EnumSet.of(REQUIRES_ACTION, PENDING, UNKNOWN, AUTHORIZED, SUCCEEDED, FAILED));
        ALLOWED.put(UNKNOWN, EnumSet.of(REQUIRES_ACTION, PENDING, AUTHORIZED, SUCCEEDED, FAILED));
        ALLOWED.put(REQUIRES_ACTION, EnumSet.of(PENDING, AUTHORIZED, SUCCEEDED, FAILED));
        ALLOWED.put(PENDING, EnumSet.of(AUTHORIZED, SUCCEEDED, FAILED));
        ALLOWED.put(AUTHORIZED, EnumSet.of(SUCCEEDED, VOIDED));
        ALLOWED.put(CAPTURE_PENDING, EnumSet.of(SUCCEEDED));
        ALLOWED.put(SUCCEEDED, EnumSet.noneOf(AttemptStatus.class));
        ALLOWED.put(FAILED, EnumSet.of(AUTHORIZED, SUCCEEDED));
        ALLOWED.put(VOIDED, EnumSet.noneOf(AttemptStatus.class));
    }

    private final int rank;

    AttemptStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean canTransitionTo(AttemptStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == VOIDED;
    }

    /** The outcome is not yet known and the PSP must be consulted (webhook or status check). */
    public boolean awaitsProvider() {
        return this == INITIATED || this == UNKNOWN || this == REQUIRES_ACTION || this == PENDING || this == CAPTURE_PENDING;
    }
}
