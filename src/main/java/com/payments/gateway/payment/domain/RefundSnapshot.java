package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;

/** Persistent state of a refund. */
public record RefundSnapshot(String id, String paymentId, String attemptId, String merchantId, String providerCode,
                             Money amount, RefundStatus status, String reason, String merchantRefundId,
                             RefundInitiator initiatedBy, String providerReference, Failure failure,
                             Instant nextStatusCheckAt, int statusCheckCount, boolean needsReview, long version,
                             Instant createdAt, Instant updatedAt) {
}
