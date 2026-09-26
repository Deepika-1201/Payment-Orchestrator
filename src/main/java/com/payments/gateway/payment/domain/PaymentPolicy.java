package com.payments.gateway.payment.domain;

import java.time.Duration;

/** Per-decision policy inputs: platform limits plus the merchant's late-success preference. */
public record PaymentPolicy(int maxAttempts, Duration authorizationTtl, boolean acceptLateSuccess) {
}
