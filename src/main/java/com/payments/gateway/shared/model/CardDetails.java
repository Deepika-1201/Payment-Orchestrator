package com.payments.gateway.shared.model;

import java.util.regex.Pattern;

/**
 * Non-sensitive card metadata reported by the PSP after the customer paid on its page (ADR-008: never a PAN), with
 * the plan when the customer converted the payment to card EMI on that page (ADR-037).
 */
public record CardDetails(String network, String last4, EmiPlan emiPlan) {

    private static final Pattern NETWORK = Pattern.compile("[a-z][a-z_]{1,19}");
    private static final Pattern LAST4 = Pattern.compile("[0-9]{4}");

    public CardDetails {
        if (network == null || !NETWORK.matcher(network).matches()) {
            throw new IllegalArgumentException("card network must be a lower-case name, e.g. visa");
        }
        if (last4 == null || !LAST4.matcher(last4).matches()) {
            throw new IllegalArgumentException("card last4 must be exactly 4 digits");
        }
    }

    public CardDetails(String network, String last4) {
        this(network, last4, null);
    }
}
