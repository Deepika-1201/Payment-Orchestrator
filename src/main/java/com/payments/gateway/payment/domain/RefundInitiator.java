package com.payments.gateway.payment.domain;

public enum RefundInitiator {
    MERCHANT,
    SYSTEM_LATE_SUCCESS,
    SYSTEM_DUPLICATE_SUCCESS,
    /** Sends a bank transfer credit, or part of one, back to its source (ADR-038). */
    SYSTEM_CREDIT_RETURN
}
