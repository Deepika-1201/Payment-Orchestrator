package com.payments.gateway.shared.events;

import java.time.Instant;

public record BillingDocumentIssued(String id, String merchantId, boolean credit, boolean settlementDeduction,
                                    long subtotal, long tax, Instant occurredAt) {
    public BillingDocumentIssued {
        if (subtotal < 0 || tax < 0) {
            throw new IllegalArgumentException("Billing document amounts cannot be negative");
        }
        Math.addExact(subtotal, tax);
    }
}