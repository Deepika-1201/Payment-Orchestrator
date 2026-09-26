package com.payments.gateway.provider.spi;

import java.util.Objects;

/**
 * Normalized webhook event. {@code merchantReference} is our attempt id (payments and disputes) or refund id
 * (refunds) as echoed by the PSP; {@code providerReference} is the PSP's id of the payment, refund or dispute.
 */
public record ProviderEvent(String eventId, Kind kind, String eventType, String providerReference,
                            String merchantReference, ProviderPaymentResult payment, ProviderRefundResult refund,
                            ProviderDisputeResult dispute) {

    public enum Kind {
        PAYMENT,
        REFUND,
        DISPUTE
    }

    public ProviderEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case PAYMENT -> Objects.requireNonNull(payment, "payment result");
            case REFUND -> Objects.requireNonNull(refund, "refund result");
            case DISPUTE -> Objects.requireNonNull(dispute, "dispute result");
        }
    }

    public static ProviderEvent payment(String eventId, String eventType, String providerReference,
                                        String merchantReference, ProviderPaymentResult result) {
        return new ProviderEvent(eventId, Kind.PAYMENT, eventType, providerReference, merchantReference, result, null, null);
    }

    public static ProviderEvent refund(String eventId, String eventType, String providerReference,
                                       String merchantReference, ProviderRefundResult result) {
        return new ProviderEvent(eventId, Kind.REFUND, eventType, providerReference, merchantReference, null, result, null);
    }

    public static ProviderEvent dispute(String eventId, String eventType, String merchantReference,
                                        ProviderDisputeResult result) {
        return new ProviderEvent(eventId, Kind.DISPUTE, eventType, result.providerDisputeId(), merchantReference, null,
                null, result);
    }
}
