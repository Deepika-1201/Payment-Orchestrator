package com.payments.gateway.shared.model;

/** Debit frequency registered with the mandate; the PSP enforces its cycles. */
public enum MandateFrequency {
    DAILY,
    WEEKLY,
    FORTNIGHTLY,
    MONTHLY,
    BIMONTHLY,
    QUARTERLY,
    HALF_YEARLY,
    YEARLY,
    AS_PRESENTED
}
