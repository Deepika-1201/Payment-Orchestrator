package com.payments.gateway.shared.events;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;

public record CurrencyConversion(String id, Kind kind, String merchantId, String providerCode,
                                 Money settled, long carriedAmount, Instant occurredAt) {

    public enum Kind {
        CAPTURE,
        REFUND,
        CHARGEBACK,
        CHARGEBACK_REVERSAL
    }
}