package com.payments.gateway.merchant;

import java.time.Duration;
import java.time.Instant;

/** {@code mandateDebitLimit}: frictionless debit limit raised by an operator (ADR-035); null means the gateway default. */
public record Merchant(String id, String name, Status status, String statusReason, String webhookUrl,
                       LateSuccessPolicy lateSuccessPolicy, Duration paymentExpiry, Instant createdAt,
                       Long mandateDebitLimit) {

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

    public boolean acceptsLateSuccess() {
        return lateSuccessPolicy == LateSuccessPolicy.ACCEPT;
    }

    public Merchant withSettings(String newName, String newWebhookUrl, LateSuccessPolicy newPolicy, Duration newExpiry) {
        return new Merchant(id, newName, status, statusReason, newWebhookUrl, newPolicy, newExpiry, createdAt,
                mandateDebitLimit);
    }

    public Merchant withMandateDebitLimit(Long newLimit) {
        return new Merchant(id, name, status, statusReason, webhookUrl, lateSuccessPolicy, paymentExpiry, createdAt,
                newLimit);
    }

    public Merchant withStatus(Status newStatus, String reason) {
        return new Merchant(id, name, newStatus, reason, webhookUrl, lateSuccessPolicy, paymentExpiry, createdAt,
                mandateDebitLimit);
    }
}
