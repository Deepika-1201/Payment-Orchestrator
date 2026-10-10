package com.payments.gateway.provider.mock;

import com.payments.gateway.shared.model.Conversion;
import com.payments.gateway.shared.model.EmiPlan;
import java.time.Instant;

/**
 * Wire format of mock PSP webhooks (serialized with snake_case). For {@code collection.credited},
 * {@code payment_reference} is the account the credit arrived in (LLD §21.8).
 */
public record MockWebhookPayload(String eventId, String type, String providerReference, String merchantReference,
                                 String status, Long amount, String currency, String failureCode,
                                 String failureMessage, String cardNetwork, String cardLast4,
                                 String paymentReference, String disputeReason, Instant respondBy,
                                 String mandateReference, String customerReference, Instant deliveredAt,
                                 EmiPlan emiPlan, String transferMode, String utr, Instant receivedAt, Conversion conversion) {

    public static final String PAYMENT_UPDATED = "payment.updated";
    public static final String REFUND_UPDATED = "refund.updated";
    /** {@code provider_reference} is the dispute id, {@code payment_reference} the disputed transaction. */
    public static final String DISPUTE_UPDATED = "dispute.updated";
    /** {@code provider_reference} is the registration, {@code payment_reference} its authorization charge. */
    public static final String MANDATE_UPDATED = "mandate.updated";
    /** {@code merchant_reference} is the notification id, {@code <debit id>.<cycle>}. */
    public static final String NOTIFICATION_UPDATED = "notification.updated";
    public static final String COLLECTION_CREDITED = "collection.credited";

    public MockWebhookPayload(String eventId, String type, String providerReference, String merchantReference,
                              String status, Long amount, String currency, String failureCode, String failureMessage,
                              String cardNetwork, String cardLast4, String paymentReference, String disputeReason,
                              Instant respondBy, String mandateReference, String customerReference, Instant deliveredAt,
                              EmiPlan emiPlan, String transferMode, String utr, Instant receivedAt) {
        this(eventId, type, providerReference, merchantReference, status, amount, currency, failureCode, failureMessage,
                cardNetwork, cardLast4, paymentReference, disputeReason, respondBy, mandateReference, customerReference,
                deliveredAt, emiPlan, transferMode, utr, receivedAt, null);
    }

    public MockWebhookPayload withConversion(Conversion reported) {
        return new MockWebhookPayload(eventId, type, providerReference, merchantReference, status, amount, currency,
                failureCode, failureMessage, cardNetwork, cardLast4, paymentReference, disputeReason, respondBy,
                mandateReference, customerReference, deliveredAt, emiPlan, transferMode, utr, receivedAt, reported);
    }

    public MockWebhookPayload(String eventId, String type, String providerReference, String merchantReference,
                              String status, Long amount, String currency, String failureCode, String failureMessage,
                              String cardNetwork, String cardLast4, String paymentReference, String disputeReason,
                              Instant respondBy, String mandateReference, String customerReference, Instant deliveredAt,
                              EmiPlan emiPlan) {
        this(eventId, type, providerReference, merchantReference, status, amount, currency, failureCode, failureMessage,
                cardNetwork, cardLast4, paymentReference, disputeReason, respondBy, mandateReference, customerReference,
                deliveredAt, emiPlan, null, null, null);
    }
}
