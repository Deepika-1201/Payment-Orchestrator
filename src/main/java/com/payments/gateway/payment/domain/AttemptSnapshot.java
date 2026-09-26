package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.PaymentMethod;
import java.time.Instant;

/** Persistent state of an attempt. */
public record AttemptSnapshot(String id, String paymentId, String merchantId, int attemptNumber, String providerCode,
                              PaymentMethod method, Money amount, AttemptStatus status, String providerReference,
                              NextAction nextAction, Failure failure, CardDetails card, String routingRuleId,
                              Instant authorizedAt, Instant capturedAt, boolean voidRequested, Instant nextStatusCheckAt,
                              int statusCheckCount, Review review, RiskAssessment risk, long version, Instant createdAt,
                              Instant updatedAt) {
}
