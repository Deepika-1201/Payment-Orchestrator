package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.MandateFrequency;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import java.time.Instant;
import java.util.Map;

/** Persistent state of a mandate. */
public record MandateSnapshot(String id, String merchantId, String providerCode, MandateInstrument instrument,
                              MandateStatus status, Money maxAmount, MandateFrequency frequency, Instant startAt,
                              Instant endAt, String description, MandateCustomer customer, Map<String, String> metadata,
                              String registrationPaymentId, String providerReference, String providerMandateReference,
                              String providerCustomerReference, NextAction nextAction, Instant authorizationExpiresAt,
                              Instant nextCheckAt, int checkCount, String failureCode, String failureMessage,
                              Instant activatedAt, long version, Instant createdAt, Instant updatedAt) {
}
