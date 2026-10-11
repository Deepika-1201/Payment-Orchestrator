package com.payments.gateway.payout;

import com.payments.gateway.shared.error.GatewayException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

public record BankStatement(String id, String reference, String accountReference, Instant from, Instant to,
                             long openingBalance, long closingBalance, List<Entry> entries, Instant importedAt) {
    public enum Kind {
        PSP_RECEIPT, PAYOUT_DEBIT, PAYOUT_RETURN, ADJUSTMENT, UNMATCHED
    }

    public record Entry(String bankEntryId, Kind kind, String referenceId, long amount, Instant bookedAt) {
        public Entry {
            text(bankEntryId, "bank_entry_id");
            text(referenceId, "reference_id");
            if (kind == null || amount == 0 || amount == Long.MIN_VALUE || bookedAt == null) {
                throw GatewayException.validation("entry", "requires a kind, nonzero signed amount and booked_at");
            }
            if ((kind == Kind.PAYOUT_DEBIT && amount >= 0) || (kind == Kind.PAYOUT_RETURN && amount <= 0)) {
                throw GatewayException.validation("amount", "has the wrong sign for the payout movement");
            }
        }
    }

    public BankStatement {
        text(reference, "reference");
        text(accountReference, "account_reference");
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                || openingBalance < 0 || closingBalance < 0 || entries == null || entries.size() > 10_000) {
            throw GatewayException.validation("statement", "requires a window of at most 31 days, nonnegative balances and at most 10000 entries");
        }
        HashSet<String> ids = new HashSet<>();
        long computed = openingBalance;
        for (Entry entry : entries) {
            if (entry == null || !ids.add(entry.bankEntryId()) || entry.bookedAt().isBefore(from) || !entry.bookedAt().isBefore(to)) {
                throw GatewayException.validation("entries", "must have unique bank ids and timestamps within [from,to)");
            }
            computed = Math.addExact(computed, entry.amount());
        }
        if (computed != closingBalance) {
            throw GatewayException.validation("closing_balance", "must equal opening_balance plus the signed entries");
        }
        entries = List.copyOf(entries);
    }

    private static void text(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw GatewayException.validation(field, "must contain 1-200 characters");
        }
    }
}