package com.payments.gateway.shared.events;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;

public record PayoutFundsMovement(Type type, String payoutId, String merchantId, Money amount, Instant occurredAt) {
    public enum Type {
        PAID, RELEASED, RETURNED
    }
}