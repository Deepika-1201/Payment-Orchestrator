package com.payments.gateway.provider.spi;

import java.util.Objects;

/**
 * Normalized webhook event. {@code merchantReference} is our attempt id (payments, disputes and credits), refund id
 * (refunds), mandate id (mandates) or notification id (notifications) as echoed by the PSP; {@code providerReference} is
 * the PSP's id of the payment, refund, dispute, mandate registration, notification or credit.
 */
public record ProviderEvent(String eventId, Kind kind, String eventType, String providerReference,
                            String merchantReference, ProviderPaymentResult payment, ProviderRefundResult refund,
                            ProviderDisputeResult dispute, ProviderMandateResult mandate,
                            ProviderNotificationResult notification, ProviderCredit credit) {

    public enum Kind {
        PAYMENT,
        REFUND,
        DISPUTE,
        MANDATE,
        NOTIFICATION,
        CREDIT
    }

    public ProviderEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case PAYMENT -> Objects.requireNonNull(payment, "payment result");
            case REFUND -> Objects.requireNonNull(refund, "refund result");
            case DISPUTE -> Objects.requireNonNull(dispute, "dispute result");
            case MANDATE -> Objects.requireNonNull(mandate, "mandate result");
            case NOTIFICATION -> Objects.requireNonNull(notification, "notification result");
            case CREDIT -> Objects.requireNonNull(credit, "credit");
        }
    }

    public static ProviderEvent payment(String eventId, String eventType, String providerReference,
                                        String merchantReference, ProviderPaymentResult result) {
        return new ProviderEvent(eventId, Kind.PAYMENT, eventType, providerReference, merchantReference, result, null, null,
                null, null, null);
    }

    public static ProviderEvent refund(String eventId, String eventType, String providerReference,
                                       String merchantReference, ProviderRefundResult result) {
        return new ProviderEvent(eventId, Kind.REFUND, eventType, providerReference, merchantReference, null, result, null,
                null, null, null);
    }

    public static ProviderEvent dispute(String eventId, String eventType, String merchantReference,
                                        ProviderDisputeResult result) {
        return new ProviderEvent(eventId, Kind.DISPUTE, eventType, result.providerDisputeId(), merchantReference, null,
                null, result, null, null, null);
    }

    public static ProviderEvent mandate(String eventId, String eventType, String merchantReference,
                                        ProviderMandateResult result) {
        return new ProviderEvent(eventId, Kind.MANDATE, eventType, result.providerReference(), merchantReference, null,
                null, null, result, null, null);
    }

    public static ProviderEvent notification(String eventId, String eventType, String merchantReference,
                                             ProviderNotificationResult result) {
        return new ProviderEvent(eventId, Kind.NOTIFICATION, eventType, result.providerReference(), merchantReference,
                null, null, null, null, result, null);
    }

    public static ProviderEvent credit(String eventId, String eventType, ProviderCredit credit) {
        return new ProviderEvent(eventId, Kind.CREDIT, eventType, credit.providerReference(), credit.merchantReference(),
                null, null, null, null, null, credit);
    }
}
