package com.payments.gateway.payment.domain;

import java.time.Instant;

/** One row of an append-only transition log ({@code payment_transitions}, or {@code mandate_transitions} for mandates). */
public record StatusChange(Entity entity, String entityId, String fromStatus, String toStatus, TransitionSource source,
                           String reason, Instant occurredAt) {

    public enum Entity {
        PAYMENT,
        ATTEMPT,
        REFUND,
        DISPUTE,
        MANDATE,
        DEBIT
    }
}
