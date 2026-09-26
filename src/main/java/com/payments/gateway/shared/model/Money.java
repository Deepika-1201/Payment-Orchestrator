package com.payments.gateway.shared.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/** Monetary amount in integer minor units (e.g. paise) with an ISO 4217 currency. */
public record Money(long amount, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an upper-case ISO 4217 code");
        }
        Currency.getInstance(currency);
    }

    public static Money of(long amount, String currency) {
        return new Money(amount, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amount, other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amount, other.amount), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount > other.amount;
    }

    /** Major-unit decimal representation, e.g. 49900 INR becomes "499.00". */
    public String toDecimalString() {
        return BigDecimal.valueOf(amount, Currency.getInstance(currency).getDefaultFractionDigits()).toPlainString();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
