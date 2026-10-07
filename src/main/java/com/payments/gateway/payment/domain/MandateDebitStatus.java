package com.payments.gateway.payment.domain;

/** Scheduling state of a mandate debit (LLD §18.1); the money state is its payment's. */
public enum MandateDebitStatus {
    SCHEDULED,
    NOTIFYING,
    READY,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** Not yet executing: the debit can still be cancelled. */
    public boolean isWaiting() {
        return this == SCHEDULED || this == NOTIFYING || this == READY;
    }
}
