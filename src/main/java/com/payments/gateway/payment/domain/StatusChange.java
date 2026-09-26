package com.payments.gateway.payment.domain;

import java.time.Instant;

/** One row of the append-only transition log. */
public record StatusChange(Entity entity, String entityId, String fromStatus, String toStatus, TransitionSource source,
                           String reason, Instant occurredAt) {

    public enum Entity {
        PAYMENT,
        ATTEMPT,
        REFUND
    }
}
