package com.payments.gateway.shared.model;

public enum MethodType {
    UPI,
    CARD,
    NETBANKING,
    /** A debit or registration charge on a mandate (ADR-035); never routed or offered at checkout. */
    MANDATE
}
