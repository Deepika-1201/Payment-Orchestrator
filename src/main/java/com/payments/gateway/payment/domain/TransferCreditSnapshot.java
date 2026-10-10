package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;

/** Persistent state of a bank transfer credit (ADR-038). */
public record TransferCreditSnapshot(String id, String merchantId, String providerCode, String providerReference,
                                     String collectionReference, String attemptId, String paymentId, Money amount,
                                     String mode, String utr, Instant receivedAt, long appliedAmount,
                                     long returnedAmount, String returnRefundId, Review review, long version,
                                     Instant createdAt, Instant updatedAt) {
}
