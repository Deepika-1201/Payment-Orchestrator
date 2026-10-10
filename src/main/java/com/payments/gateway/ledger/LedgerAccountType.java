package com.payments.gateway.ledger;

/** Shadow-ledger accounts kept per merchant PSP account (ADR-012). */
public enum LedgerAccountType {
    PSP_RECEIVABLE(EntryDirection.DEBIT),
    SALES_CLEARING(EntryDirection.CREDIT),
    PSP_FEES(EntryDirection.DEBIT),
    REFUNDS(EntryDirection.DEBIT),
    CHARGEBACKS(EntryDirection.DEBIT),
    BANK_SETTLEMENTS(EntryDirection.DEBIT),
    /** Bank transfer credits held for customers until they pay a payment or go back (ADR-038). */
    CUSTOMER_FUNDS(EntryDirection.CREDIT);

    private final EntryDirection normalSide;

    LedgerAccountType(EntryDirection normalSide) {
        this.normalSide = normalSide;
    }

    public EntryDirection normalSide() {
        return normalSide;
    }
}
