package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;

/** Persistent state of a mandate debit. */
public record MandateDebitSnapshot(String id, String mandateId, String merchantId, String paymentId,
                                   String merchantDebitId, Money amount, long maxAmount, Long frictionlessLimit,
                                   boolean requiresNotification, MandateDebitStatus status, String description,
                                   Instant dueAt, Instant notBefore, int cycle, String notificationReference,
                                   Instant notificationRequestedAt, Instant notifiedAt, Instant nextActionAt,
                                   int checkCount, Instant lastExecutedAt, String failureCode, String failureMessage,
                                   long version, Instant createdAt, Instant updatedAt) {
}
