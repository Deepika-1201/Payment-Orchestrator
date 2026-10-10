package com.payments.gateway.shared.events;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;

/**
 * Money moved at a PSP (an attempt was captured, a refund succeeded, a dispute withheld or returned funds, or a bank
 * transfer credit arrived, paid a payment or went back). Published synchronously inside the state-change transaction
 * so the ledger posting commits atomically with it. {@code paymentId} is null for an unmatched credit.
 */
public record FundsMovement(Type type, String merchantId, String providerCode, String referenceId, String paymentId,
                            Money amount, Instant occurredAt) {

    public enum Type {
        CAPTURE,
        REFUND,
        CHARGEBACK,
        CHARGEBACK_REVERSAL,
        CREDIT_RECEIVED,
        CREDIT_APPLIED,
        CREDIT_RETURNED
    }
}
