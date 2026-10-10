package com.payments.gateway.payment.domain;

import java.time.Duration;

/**
 * Per-decision policy inputs: platform limits plus the merchant's late-success preference, and how its bank transfer
 * credits pay a payment (ADR-038).
 */
public record PaymentPolicy(int maxAttempts, Duration authorizationTtl, boolean acceptLateSuccess,
                            boolean transferCreditsAddUp, boolean acceptShortTransfer) {

    public PaymentPolicy(int maxAttempts, Duration authorizationTtl, boolean acceptLateSuccess) {
        this(maxAttempts, authorizationTtl, acceptLateSuccess, true, false);
    }
}
