package com.payments.gateway.shared.model;

public enum MethodType {
    UPI,
    CARD,
    NETBANKING,
    /** A debit or registration charge on a mandate (ADR-035); never routed or offered at checkout. */
    MANDATE,
    /** The four below complete on the PSP's page (ADR-037); all but card EMI name their provider. */
    WALLET,
    EMI,
    CARDLESS_EMI,
    PAY_LATER
}
