package com.payments.gateway.ledger;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import java.util.List;

public final class LedgerModel {

    private LedgerModel() {
    }

    public record Leg(LedgerAccountType account, EntryDirection direction, Money amount) {

        public static Leg debit(LedgerAccountType account, Money amount) {
            return new Leg(account, EntryDirection.DEBIT, amount);
        }

        public static Leg credit(LedgerAccountType account, Money amount) {
            return new Leg(account, EntryDirection.CREDIT, amount);
        }
    }

    /** A balanced set of legs, idempotent on {@code (referenceType, referenceId, type)}. */
    public record Posting(String merchantId, String providerCode, LedgerTransactionType type, String referenceType,
                          String referenceId, String description, Instant occurredAt, List<Leg> legs) {

        public Posting {
            legs = List.copyOf(legs);
        }
    }

    public record AccountBalance(String providerCode, LedgerAccountType account, String currency, long debits,
                                 long credits) {

        /** Balance on the account's normal side (positive = normal). */
        public long balance() {
            return account.normalSide() == EntryDirection.DEBIT ? debits - credits : credits - debits;
        }
    }

    public record EntryView(LedgerAccountType account, EntryDirection direction, long amount, String currency) {
    }

    public record TransactionView(String id, LedgerTransactionType type, String providerCode, String referenceType,
                                  String referenceId, String description, Instant occurredAt, List<EntryView> entries) {
    }
}
