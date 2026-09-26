package com.payments.gateway.payment.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Dispute lifecycle as reported by the PSP (ADR-018). {@code WON} and {@code LOST} are final. */
public enum DisputeStatus {
    OPEN(1),
    UNDER_REVIEW(2),
    WON(3),
    LOST(3);

    private static final Map<DisputeStatus, Set<DisputeStatus>> ALLOWED = new EnumMap<>(DisputeStatus.class);

    static {
        ALLOWED.put(OPEN, EnumSet.of(UNDER_REVIEW, WON, LOST));
        ALLOWED.put(UNDER_REVIEW, EnumSet.of(WON, LOST));
        ALLOWED.put(WON, EnumSet.noneOf(DisputeStatus.class));
        ALLOWED.put(LOST, EnumSet.noneOf(DisputeStatus.class));
    }

    private final int rank;

    DisputeStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean isFinal() {
        return this == WON || this == LOST;
    }

    public boolean canTransitionTo(DisputeStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
