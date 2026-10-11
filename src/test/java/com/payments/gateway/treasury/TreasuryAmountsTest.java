package com.payments.gateway.treasury;

import com.payments.gateway.treasury.TreasuryAmounts.Kind;
import com.payments.gateway.treasury.TreasuryAmounts.Line;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreasuryAmountsTest {
    @Test
    void explainsNetSettlementWithoutChargingInvoiceTaxTwice() {
        var totals = TreasuryAmounts.total(List.of(new Line(Kind.CAPTURE, 100_000, 0),
                new Line(Kind.CREDIT_APPLIED, 5000, 0), new Line(Kind.REFUND, 7000, 0),
                new Line(Kind.CHARGEBACK, 6000, 0), new Line(Kind.CHARGEBACK_REVERSAL, 2000, 0),
                new Line(Kind.PSP_FEE, 1900, 0), new Line(Kind.INVOICE, 1180, 180),
                new Line(Kind.CREDIT_NOTE, 236, 36), new Line(Kind.RESERVE_HELD, 4000, 0),
                new Line(Kind.RESERVE_RELEASED, 1500, 0), new Line(Kind.PAYOUT_RELEASED, 300, 0)));
        assertThat(totals).isEqualTo(new TreasuryAmounts.Totals(105_000, 7000, 4000, 1900, 800, 144, 2500, 300));
        assertThat(totals.net()).isEqualTo(88_956);
    }

    @Test
    void randomizedSourcesAgreeWithAnIndependentIntegerOracle() {
        var random = new Random(2718044);
        for (int sequence = 0; sequence < 100; sequence++) {
            var lines = new ArrayList<Line>();
            BigInteger expected = BigInteger.ZERO;
            for (int step = 0; step < 150; step++) {
                Kind kind = Kind.values()[random.nextInt(Kind.values().length)];
                long amount = random.nextLong(1, 100_000_000);
                long tax = kind == Kind.INVOICE || kind == Kind.CREDIT_NOTE ? amount / 7 : 0;
                Line line = new Line(kind, amount, tax);
                lines.add(line);
                long signed = switch (kind) {
                    case CAPTURE, CREDIT_APPLIED, CHARGEBACK_REVERSAL, CREDIT_NOTE, RESERVE_RELEASED, PAYOUT_RELEASED -> amount;
                    case REFUND, CHARGEBACK, PSP_FEE, INVOICE, RESERVE_HELD -> -amount;
                };
                expected = expected.add(BigInteger.valueOf(signed));
                assertThat(line.payableDelta()).isEqualTo(signed);
                assertThat(TreasuryAmounts.total(lines).net()).isEqualTo(expected.longValueExact());
            }
        }
    }

    @Test
    void rejectsInvalidLinesAndNeverWrapsOverflow() {
        assertThatThrownBy(() -> new Line(Kind.CAPTURE, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Line(Kind.CAPTURE, 100, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Line(Kind.INVOICE, 100, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Line(Kind.INVOICE, 100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TreasuryAmounts.total(List.of(new Line(Kind.CAPTURE, Long.MAX_VALUE, 0),
                new Line(Kind.CAPTURE, 1, 0)))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new TreasuryAmounts.Totals(Long.MAX_VALUE, 0, -1, 0, 0, 0, 0, 0).net())
                .isInstanceOf(ArithmeticException.class);
    }
}