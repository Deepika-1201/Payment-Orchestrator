package com.payments.gateway.routing;

import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

public record ProviderFeeSchedule(String id, String merchantId, String providerCode, MethodType method, String currency,
                                  long fixedAmount, int rateBps, int taxBps, Collection collection,
                                  Instant effectiveAt, Instant createdAt) {

    public enum Collection { WITHHELD, POSTPAID }

    public ProviderFeeSchedule {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Money.of(fixedAmount, currency);
        if (rateBps < 0 || rateBps > 10_000 || taxBps < 0 || taxBps > 10_000) {
            throw new IllegalArgumentException("fee and tax basis points must be between 0 and 10000");
        }
    }

    public Money estimate(Money amount) {
        requireCharge(amount);
        return Money.of(fee(BigDecimal.valueOf(fixedAmount), amount.amount()), currency);
    }

    public Money expectedWithheld(Money charged, Money settled) {
        requireCharge(charged);
        if (!settled.inSettlementCurrency() || settled.amount() <= 0) {
            throw new IllegalArgumentException("settlement must be positive INR");
        }
        if (collection == Collection.POSTPAID) {
            return Money.of(0, settled.currency());
        }
        BigDecimal fixed = BigDecimal.valueOf(fixedAmount).multiply(BigDecimal.valueOf(settled.amount()))
                .divide(BigDecimal.valueOf(charged.amount()), 20, RoundingMode.HALF_UP);
        return Money.of(fee(fixed, settled.amount()), settled.currency());
    }

    private long fee(BigDecimal fixed, long amount) {
        BigDecimal base = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rateBps)).movePointLeft(4)
                .add(fixed).setScale(0, RoundingMode.HALF_UP);
        BigDecimal tax = base.multiply(BigDecimal.valueOf(taxBps)).movePointLeft(4).setScale(0, RoundingMode.HALF_UP);
        return base.add(tax).longValueExact();
    }

    private void requireCharge(Money amount) {
        if (!currency.equals(amount.currency()) || amount.amount() <= 0) {
            throw new IllegalArgumentException("charge must be positive and in " + currency);
        }
    }
}