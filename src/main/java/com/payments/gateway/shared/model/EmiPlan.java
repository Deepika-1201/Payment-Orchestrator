package com.payments.gateway.shared.model;

import java.util.regex.Pattern;

/** A card EMI plan as the PSP reports it (ADR-037): annual interest rate in basis points, issuing bank's code. */
public record EmiPlan(int tenureMonths, Integer interestRateBps, String issuer) {

    private static final Pattern ISSUER = Pattern.compile("[A-Z0-9_]{2,16}");

    public EmiPlan {
        if (tenureMonths < 1 || tenureMonths > 120) {
            throw new IllegalArgumentException("EMI tenure must be 1 to 120 months");
        }
        if (interestRateBps != null && (interestRateBps < 0 || interestRateBps > 10_000)) {
            throw new IllegalArgumentException("EMI interest rate must be 0 to 10000 basis points");
        }
        if (issuer != null && !ISSUER.matcher(issuer).matches()) {
            throw new IllegalArgumentException("EMI issuer must be an upper-case bank code");
        }
    }
}
