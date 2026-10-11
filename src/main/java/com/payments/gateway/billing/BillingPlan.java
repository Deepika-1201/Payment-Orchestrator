package com.payments.gateway.billing;

import com.payments.gateway.billing.BillingAmounts.Price;
import com.payments.gateway.shared.model.MethodType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record BillingPlan(String id, String merchantId, String name, Instant effectiveAt,
                          Map<MethodType, Price> paymentFees, Map<MethodType, Price> refundFees,
                          long monthlyMinimum, Instant createdAt) {

    public BillingPlan {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        paymentFees = Map.copyOf(paymentFees);
        refundFees = Map.copyOf(refundFees);
        if (monthlyMinimum < 0 || monthlyMinimum > 1_000_000_000_000L) {
            throw new IllegalArgumentException("invalid monthly minimum");
        }
    }
}