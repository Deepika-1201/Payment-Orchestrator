package com.payments.gateway.payment.domain;

public enum PaymentStatus {
    REQUIRES_PAYMENT_METHOD,
    PROCESSING,
    REQUIRES_ACTION,
    AUTHORIZED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
