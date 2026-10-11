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
    CUSTOMER_FUNDS(EntryDirection.CREDIT),
    FX_CONVERSION(EntryDirection.CREDIT),
    FX_GAIN_LOSS(EntryDirection.CREDIT),
    ESCROW(EntryDirection.DEBIT),
    MERCHANT_PAYABLE(EntryDirection.CREDIT),
    RESERVE(EntryDirection.CREDIT),
    SETTLEMENT_PAYABLE(EntryDirection.CREDIT),
    PLATFORM_FEE_INCOME(EntryDirection.CREDIT),
    GST_PAYABLE(EntryDirection.CREDIT);

    private final EntryDirection normalSide;

    LedgerAccountType(EntryDirection normalSide) {
        this.normalSide = normalSide;
    }

    public EntryDirection normalSide() {
        return normalSide;
    }
}
