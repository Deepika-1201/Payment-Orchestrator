package com.payments.gateway.payment.domain;

/**
 * How a bank transfer credit splits between the payment and a return (LLD §21.2). {@code funding} is the attempt's
 * result when this credit completed the amount, else null.
 */
public record CreditAllocation(long applied, long returned, String returnReason, AttemptApplyResult funding) {

    public static final String EXCESS = "excess";
    public static final String INEXACT = "inexact";
    public static final String LATE = "late";
    public static final String SHORT_AT_EXPIRY = "short_at_expiry";

    public boolean funded() {
        return funding != null;
    }
}
