package com.payments.gateway.shared.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a PSP converted a movement in another currency to: the amount it settled in the settlement currency, and the rate
 * it used when it gives one, kept as its exact decimal (ADR-040, NFR-20).
 */
public record Conversion(Money settled, BigDecimal rate) {

    public Conversion {
        Objects.requireNonNull(settled, "settled");
        if (!settled.inSettlementCurrency() || settled.amount() <= 0) {
            throw new IllegalArgumentException("a conversion settles a positive amount in " + Money.SETTLEMENT_CURRENCY);
        }
        if (rate != null && rate.signum() <= 0) {
            throw new IllegalArgumentException("a conversion rate must be positive");
        }
    }
}
