package com.payments.gateway.ledger;

public enum LedgerTransactionType {
    PAYMENT_CAPTURED,
    REFUND_SUCCEEDED,
    PSP_FEE,
    SETTLEMENT,
    CHARGEBACK,
    REVERSAL,
    ADJUSTMENT,
    CREDIT_RECEIVED,
    CREDIT_APPLIED,
    CREDIT_RETURNED,
    FX_CONVERSION
}
