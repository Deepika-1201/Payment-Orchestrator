package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;

public record DisputeSnapshot(String id, String paymentId, String attemptId, String merchantId, String providerCode,
                              String providerDisputeId, Money amount, String reason, DisputeStatus status,
                              Instant respondBy, Review review, long version, Instant createdAt, Instant updatedAt,
                              MerchantResponse response, Instant evidenceDueNotifiedAt) {
}
