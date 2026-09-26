package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import java.util.Map;

/** Persistent state of a payment (without attempts). */
public record PaymentSnapshot(String id, String merchantId, String merchantOrderId, Money amount, PaymentStatus status,
                              CaptureMethod captureMethod, String description, Customer customer,
                              Map<String, String> metadata, long amountCaptured, long amountRefunded,
                              String succeededAttemptId, String cancellationReason, String failureCode,
                              String failureMessage, Instant expiresAt, Instant authorizationExpiresAt, long version,
                              Instant createdAt, Instant updatedAt) {
}
