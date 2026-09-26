package com.payments.gateway.ledger;

public enum LedgerTransactionType {
    PAYMENT_CAPTURED,
    REFUND_SUCCEEDED,
    PSP_FEE,
    SETTLEMENT,
    CHARGEBACK,
    REVERSAL
}
