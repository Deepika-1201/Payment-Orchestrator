package com.payments.gateway.billing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.payments.gateway.shared.events.BillableActivity.Kind;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import java.time.Instant;

public record BillingEvent(String id, String merchantId, Kind kind, String referenceId, String paymentId,
                           String planId, MethodType method, Money amount, Long settledAmount, Long serviceFee,
                           String priceReason, Instant recognizedAt, String invoiceId) {

    public BillingEvent(String id, String merchantId, Kind kind, String referenceId, String paymentId,
                        String planId, MethodType method, Money amount, Long settledAmount, Long serviceFee,
                        String priceReason, Instant recognizedAt) {
        this(id, merchantId, kind, referenceId, paymentId, planId, method, amount, settledAmount, serviceFee, priceReason, recognizedAt, null);
    }

    @JsonProperty("pricing_status")
    public String pricingStatus() {
        return settledAmount == null ? "conversion_pending" : serviceFee == null ? "price_pending" : "priced";
    }

    public BillingEvent withInvoice(String assignedInvoice) {
        return new BillingEvent(id, merchantId, kind, referenceId, paymentId, planId, method, amount, settledAmount,
                serviceFee, priceReason, recognizedAt, assignedInvoice);
    }
}