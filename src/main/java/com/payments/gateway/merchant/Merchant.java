package com.payments.gateway.merchant;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code mandateDebitLimit}: frictionless debit limit raised by an operator (ADR-035); null means the gateway default.
 * {@code transferCredits} and {@code transferShortfall}: how bank transfer credits pay a payment (ADR-038).
 * {@code internationalCards}: the merchant may charge cards in other currencies (ADR-040).
 */
public record Merchant(String id, String name, Status status, String statusReason, String webhookUrl,
                       LateSuccessPolicy lateSuccessPolicy, Duration paymentExpiry, Instant createdAt,
                       Long mandateDebitLimit, TransferCredits transferCredits, TransferShortfall transferShortfall,
                       boolean internationalCards) {

    public Merchant {
        transferCredits = transferCredits == null ? TransferCredits.ADD_UP : transferCredits;
        transferShortfall = transferShortfall == null ? TransferShortfall.REFUND : transferShortfall;
    }

    public Merchant(String id, String name, Status status, String statusReason, String webhookUrl,
                LateSuccessPolicy lateSuccessPolicy, Duration paymentExpiry, Instant createdAt,
                Long mandateDebitLimit, TransferCredits transferCredits, TransferShortfall transferShortfall) {
        this(id, name, status, statusReason, webhookUrl, lateSuccessPolicy, paymentExpiry, createdAt, mandateDebitLimit,
            transferCredits, transferShortfall, false);
        }

        public Merchant(String id, String name, Status status, String statusReason, String webhookUrl,
                    LateSuccessPolicy lateSuccessPolicy, Duration paymentExpiry, Instant createdAt,
                    Long mandateDebitLimit) {
        this(id, name, status, statusReason, webhookUrl, lateSuccessPolicy, paymentExpiry, createdAt, mandateDebitLimit,
                null, null, false);
    }

    public Merchant(String id, String name, Status status, String statusReason, String webhookUrl,
                    LateSuccessPolicy lateSuccessPolicy, Duration paymentExpiry, Instant createdAt) {
        this(id, name, status, statusReason, webhookUrl, lateSuccessPolicy, paymentExpiry, createdAt, null);
    }

    public enum Status {
        ACTIVE,
        SUSPENDED
    }

    /** What to do when a PSP confirms success after the payment expired or failed (FR-P8). */
    public enum LateSuccessPolicy {
        AUTO_REFUND,
        ACCEPT
    }

    /** Whether several bank transfer credits add up to the amount, or only one credit of exactly the amount pays it. */
    public enum TransferCredits {
        ADD_UP,
        EXACT
    }

    /** A bank transfer payment still short at expiry: its credits go back, or it succeeds for what arrived. */
    public enum TransferShortfall {
        REFUND,
        ACCEPT
    }

    public boolean acceptsLateSuccess() {
        return lateSuccessPolicy == LateSuccessPolicy.ACCEPT;
    }

    public Merchant withSettings(String newName, String newWebhookUrl, LateSuccessPolicy newPolicy, Duration newExpiry) {
        return new Merchant(id, newName, status, statusReason, newWebhookUrl, newPolicy, newExpiry, createdAt,
                mandateDebitLimit, transferCredits, transferShortfall, internationalCards);
    }

    public Merchant withMandateDebitLimit(Long newLimit) {
        return new Merchant(id, name, status, statusReason, webhookUrl, lateSuccessPolicy, paymentExpiry, createdAt,
                newLimit, transferCredits, transferShortfall, internationalCards);
    }

    public Merchant withBankTransfers(TransferCredits credits, TransferShortfall shortfall) {
        return new Merchant(id, name, status, statusReason, webhookUrl, lateSuccessPolicy, paymentExpiry, createdAt,
                mandateDebitLimit, credits, shortfall, internationalCards);
    }

    public Merchant withInternationalCards(boolean enabled) {
        return new Merchant(id, name, status, statusReason, webhookUrl, lateSuccessPolicy, paymentExpiry, createdAt,
                mandateDebitLimit, transferCredits, transferShortfall, enabled);
    }

    public Merchant withStatus(Status newStatus, String reason) {
        return new Merchant(id, name, newStatus, reason, webhookUrl, lateSuccessPolicy, paymentExpiry, createdAt,
                mandateDebitLimit, transferCredits, transferShortfall, internationalCards);
    }
}
