package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * A chargeback or UPI dispute as reported by the PSP (FR-D1). {@code paymentReference} is the PSP's reference of the
 * disputed payment; {@code respondBy} is the PSP's evidence deadline, when it gives one.
 */
public record ProviderDisputeResult(String providerDisputeId, String paymentReference, Status status, Money amount,
                                    String reason, Instant respondBy, String rawStatus) {

    public enum Status {
        OPEN,
        UNDER_REVIEW,
        WON,
        LOST
    }

    public ProviderDisputeResult {
        Objects.requireNonNull(providerDisputeId, "providerDisputeId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
    }
}
