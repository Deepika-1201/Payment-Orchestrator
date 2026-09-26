package com.payments.gateway.checkout;

import java.time.Instant;

public record CheckoutSession(String id, String merchantId, String paymentId, String returnUrl, Instant expiresAt,
                              Instant createdAt) {
}
