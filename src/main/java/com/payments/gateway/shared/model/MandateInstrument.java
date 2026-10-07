package com.payments.gateway.shared.model;

/** How a customer authorizes recurring debits (ADR-035). Card and UPI debits need a pre-debit notification. */
public enum MandateInstrument {
    UPI_AUTOPAY,
    CARD,
    ENACH;

    public boolean requiresDebitNotification() {
        return this != ENACH;
    }
}
