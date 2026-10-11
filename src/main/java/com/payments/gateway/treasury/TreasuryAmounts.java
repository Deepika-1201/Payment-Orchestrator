package com.payments.gateway.treasury;

import java.util.List;

public final class TreasuryAmounts {
    private TreasuryAmounts() {
    }

    public enum Kind {
        CAPTURE, CREDIT_APPLIED, REFUND, CHARGEBACK, CHARGEBACK_REVERSAL, PSP_FEE, INVOICE, CREDIT_NOTE,
        RESERVE_HELD, RESERVE_RELEASED, PAYOUT_RELEASED;

        public boolean increasesPayable() {
            return switch (this) {
                case CAPTURE, CREDIT_APPLIED, CHARGEBACK_REVERSAL, CREDIT_NOTE, RESERVE_RELEASED, PAYOUT_RELEASED -> true;
                default -> false;
            };
        }
    }

    public record Line(Kind kind, long amount, long tax) {
        public Line {
            if (kind == null || amount <= 0 || tax < 0 || tax > amount
                    || (tax != 0 && kind != Kind.INVOICE && kind != Kind.CREDIT_NOTE)) {
                throw new IllegalArgumentException("a treasury line requires a positive total and valid invoice tax");
            }
        }

        public long payableDelta() {
            return kind.increasesPayable() ? amount : -amount;
        }
    }

    public record Totals(long captures, long refunds, long chargebacks, long pspFees, long serviceFees, long gst,
                         long reserveChange, long releasedPayouts) {
        public long net() {
            long result = Math.subtractExact(captures, refunds);
            result = Math.subtractExact(result, chargebacks);
            result = Math.subtractExact(result, pspFees);
            result = Math.subtractExact(result, serviceFees);
            result = Math.subtractExact(result, gst);
            result = Math.subtractExact(result, reserveChange);
            return Math.addExact(result, releasedPayouts);
        }
    }

    public static Totals total(List<Line> lines) {
        long captures = 0;
        long refunds = 0;
        long chargebacks = 0;
        long pspFees = 0;
        long serviceFees = 0;
        long gst = 0;
        long reserves = 0;
        long releasedPayouts = 0;
        for (Line line : lines) {
            switch (line.kind()) {
                case CAPTURE, CREDIT_APPLIED -> captures = Math.addExact(captures, line.amount());
                case REFUND -> refunds = Math.addExact(refunds, line.amount());
                case CHARGEBACK -> chargebacks = Math.addExact(chargebacks, line.amount());
                case CHARGEBACK_REVERSAL -> chargebacks = Math.subtractExact(chargebacks, line.amount());
                case PSP_FEE -> pspFees = Math.addExact(pspFees, line.amount());
                case INVOICE -> {
                    serviceFees = Math.addExact(serviceFees, Math.subtractExact(line.amount(), line.tax()));
                    gst = Math.addExact(gst, line.tax());
                }
                case CREDIT_NOTE -> {
                    serviceFees = Math.subtractExact(serviceFees, Math.subtractExact(line.amount(), line.tax()));
                    gst = Math.subtractExact(gst, line.tax());
                }
                case RESERVE_HELD -> reserves = Math.addExact(reserves, line.amount());
                case RESERVE_RELEASED -> reserves = Math.subtractExact(reserves, line.amount());
                case PAYOUT_RELEASED -> releasedPayouts = Math.addExact(releasedPayouts, line.amount());
            }
        }
        return new Totals(captures, refunds, chargebacks, pspFees, serviceFees, gst, reserves, releasedPayouts);
    }
}