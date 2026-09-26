package com.payments.gateway.provider.spi;

import java.util.Objects;

/**
 * Normalized webhook event. {@code merchantReference} is our attempt id (payments) or refund id (refunds)
 * as echoed by the PSP.
 */
public record ProviderEvent(String eventId, Kind kind, String eventType, String providerReference,
                            String merchantReference, ProviderPaymentResult payment, ProviderRefundResult refund) {

    public enum Kind {
        PAYMENT,
        REFUND
    }

    public ProviderEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.PAYMENT) {
            Objects.requireNonNull(payment, "payment result");
        } else {
            Objects.requireNonNull(refund, "refund result");
        }
    }
}
