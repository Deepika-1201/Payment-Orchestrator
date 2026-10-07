package com.payments.gateway.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/** Mandate lifecycle (ADR-035, LLD §18.1). Evidence of a lower rank, or after a final status, is stale. */
public enum MandateStatus {
    CREATED(0),
    PENDING_AUTHORIZATION(1),
    ACTIVE(2),
    PAUSED(2),
    REVOKED(3),
    EXPIRED(3),
    FAILED(3);

    private final int rank;

    MandateStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean isFinal() {
        return rank == 3;
    }

    public boolean canTransitionTo(MandateStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<MandateStatus> allowedTargets() {
        return switch (this) {
            case CREATED -> EnumSet.of(PENDING_AUTHORIZATION, ACTIVE, PAUSED, REVOKED, FAILED);
            case PENDING_AUTHORIZATION -> EnumSet.of(ACTIVE, PAUSED, REVOKED, FAILED);
            case ACTIVE -> EnumSet.of(PAUSED, REVOKED, EXPIRED);
            case PAUSED -> EnumSet.of(ACTIVE, REVOKED, EXPIRED);
            case REVOKED, EXPIRED, FAILED -> EnumSet.noneOf(MandateStatus.class);
        };
    }
}
