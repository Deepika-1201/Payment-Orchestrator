package com.payments.gateway.provider.mock;

/** Wire format of mock PSP webhooks (serialized with snake_case). */
public record MockWebhookPayload(String eventId, String type, String providerReference, String merchantReference,
                                 String status, Long amount, String currency, String failureCode,
                                 String failureMessage) {

    public static final String PAYMENT_UPDATED = "payment.updated";
    public static final String REFUND_UPDATED = "refund.updated";
}
