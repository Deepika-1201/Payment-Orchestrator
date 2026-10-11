package com.payments.gateway.payout;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankStatementTest {
    private final Instant from = Instant.parse("2026-10-11T00:00:00Z");

    @Test
    void validatesArithmeticEntrySignsWindowAndUniqueIds() {
        var receipt = new BankStatement.Entry("bank-1", BankStatement.Kind.PSP_RECEIPT, "receipt", 1000, from);
        var paid = new BankStatement.Entry("bank-2", BankStatement.Kind.PAYOUT_DEBIT, "payout", -700, from.plusSeconds(1));
        var statement = new BankStatement("id", "reference", "escrow", from, from.plusSeconds(10), 50, 350, List.of(receipt, paid), from);
        assertThat(statement.closingBalance()).isEqualTo(350);
        assertThatThrownBy(() -> new BankStatement("id", "reference", "escrow", from, from.plusSeconds(10), 0, 999, List.of(receipt), from))
                .hasMessageContaining("closing_balance");
        assertThatThrownBy(() -> new BankStatement("id", "reference", "escrow", from, from.plusSeconds(10), 0, 2000, List.of(receipt, receipt), from))
                .hasMessageContaining("unique bank ids");
        assertThatThrownBy(() -> new BankStatement.Entry("id", BankStatement.Kind.PAYOUT_DEBIT, "payout", 700, from))
                .hasMessageContaining("wrong sign");
        assertThatThrownBy(() -> new BankStatement("id", "reference", "escrow", from.plusSeconds(1), from.plusSeconds(10), 0, 1000, List.of(receipt), from))
                .hasMessageContaining("timestamps");
    }

    @Test
    void endOfWindowIsExclusiveAndWindowsCannotExceed31Days() {
        Instant end = from.plusSeconds(10);
        var entry = new BankStatement.Entry("at-end", BankStatement.Kind.PSP_RECEIPT, "receipt", 1000, end);
        assertThatThrownBy(() -> new BankStatement("id", "reference", "escrow", from, end, 0, 1000, List.of(entry), from))
                .hasMessageContaining("timestamps");
        assertThatThrownBy(() -> new BankStatement("id", "reference", "escrow", from, from.plusSeconds(31 * 86400L + 1),
                0, 0, List.of(), from)).hasMessageContaining("31 days");
        assertThatThrownBy(() -> new BankStatement.Entry("return", BankStatement.Kind.PAYOUT_RETURN, "payout", -700, from))
                .hasMessageContaining("wrong sign");
    }

    @Test
    void intermediateOverflowIsRejectedEvenWhenTheFinalBalanceWouldWrapBack() {
        var credit = new BankStatement.Entry("credit", BankStatement.Kind.UNMATCHED, "credit", 1, from);
        var debit = new BankStatement.Entry("debit", BankStatement.Kind.UNMATCHED, "debit", -1, from.plusSeconds(1));
        assertThatThrownBy(() -> new BankStatement("id", "reference", "escrow", from, from.plusSeconds(10),
                Long.MAX_VALUE, Long.MAX_VALUE, List.of(credit, debit), from)).isInstanceOf(ArithmeticException.class);
    }
}