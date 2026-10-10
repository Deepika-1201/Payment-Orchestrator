package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;

/** Persistent state of a refund; {@code creditId} is the bank transfer credit it sends money back from (ADR-038). */
public record RefundSnapshot(String id, String paymentId, String attemptId, String merchantId, String providerCode,
                             Money amount, RefundStatus status, String reason, String merchantRefundId,
                             RefundInitiator initiatedBy, String providerReference, Failure failure,
                             Instant nextStatusCheckAt, int statusCheckCount, Review review, long version,
                             Instant createdAt, Instant updatedAt, String creditId) {
}
