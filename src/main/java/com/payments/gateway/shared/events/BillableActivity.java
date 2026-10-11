package com.payments.gateway.shared.events;

import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import java.time.Instant;

public record BillableActivity(String merchantId, Kind kind, String referenceId, String paymentId,
                               MethodType method, Money amount, Money settled, Instant recognizedAt) {
    public enum Kind { PAYMENT, REFUND }
}