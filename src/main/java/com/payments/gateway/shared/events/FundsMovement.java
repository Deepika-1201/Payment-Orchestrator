package com.payments.gateway.shared.events;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;

/**
 * Money moved at a PSP (an attempt was captured, a refund succeeded, or a dispute withheld or returned funds).
 * Published synchronously inside the state-change transaction so the ledger posting commits atomically with it.
 */
public record FundsMovement(Type type, String merchantId, String providerCode, String referenceId, String paymentId,
                            Money amount, Instant occurredAt) {

    public enum Type {
        CAPTURE,
        REFUND,
        CHARGEBACK,
        CHARGEBACK_REVERSAL
    }
}
