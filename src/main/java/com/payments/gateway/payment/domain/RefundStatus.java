package com.payments.gateway.payment.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum RefundStatus {
    INITIATED(0),
    UNKNOWN(1),
    PENDING(2),
    SUCCEEDED(3),
    FAILED(3);

    private static final Map<RefundStatus, Set<RefundStatus>> ALLOWED = new EnumMap<>(RefundStatus.class);

    static {
        ALLOWED.put(INITIATED, EnumSet.of(PENDING, UNKNOWN, SUCCEEDED, FAILED));
        ALLOWED.put(UNKNOWN, EnumSet.of(PENDING, SUCCEEDED, FAILED));
        ALLOWED.put(PENDING, EnumSet.of(SUCCEEDED, FAILED));
        ALLOWED.put(SUCCEEDED, EnumSet.noneOf(RefundStatus.class));
        ALLOWED.put(FAILED, EnumSet.of(SUCCEEDED));
    }

    private final int rank;

    RefundStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean canTransitionTo(RefundStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
