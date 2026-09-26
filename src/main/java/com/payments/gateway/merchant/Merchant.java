package com.payments.gateway.merchant;

import java.time.Duration;
import java.time.Instant;

public record Merchant(String id, String name, Status status, String webhookUrl, LateSuccessPolicy lateSuccessPolicy,
                       Duration paymentExpiry, Instant createdAt) {

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
}
