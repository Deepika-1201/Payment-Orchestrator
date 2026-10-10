package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import java.util.List;

/**
 * A PSP's settlement report for one merchant account and time window: individual settled items plus the payouts
 * that settled them. {@code netAmount} is signed (negative when refunds and fees exceed captures).
 */
public record SettlementReport(List<Line> lines, List<Settlement> settlements) {

    /**
     * {@code CHARGEBACK} lines are disputed amounts the PSP withheld; {@code CHARGEBACK_REVERSAL} lines return them after
     * a won dispute. For both, {@code providerReference} is the PSP's dispute id and {@code merchantReference} the
     * disputed attempt's id. {@code ADJUSTMENT_CREDIT} and {@code ADJUSTMENT_DEBIT} are money the PSP added to or took
     * from the payout for anything else (reserves, transfers, manual corrections, reversed refunds), which the gateway
     * has no record of (ADR-032).
     */
    public enum LineType {
        PAYMENT(true),
        REFUND(false),
        CHARGEBACK(false),
        CHARGEBACK_REVERSAL(true),
        ADJUSTMENT_CREDIT(true),
        ADJUSTMENT_DEBIT(false);

        private final boolean credit;

        LineType(boolean credit) {
            this.credit = credit;
        }

        /** Whether the line's amount is added to the payout rather than taken from it. */
        public boolean credit() {
            return credit;
        }
    }

    /**
     * {@code fee} is what the PSP withheld from the payout for this line (zero when its charges are invoiced instead),
     * so a line moves the payout by its signed amount minus the fee. {@code description} is the PSP's own wording.
     * {@code amount} and {@code fee} are in the payout's currency; for an item charged in another currency,
     * {@code charged} is the amount in that currency (ADR-040), else null.
     */
    public record Line(String lineId, LineType type, String providerReference, String merchantReference, Money amount,
                       Money fee, String settlementId, Instant occurredAt, String description, Money charged) {

        public Line(String lineId, LineType type, String providerReference, String merchantReference, Money amount,
                    Money fee, String settlementId, Instant occurredAt, String description) {
            this(lineId, type, providerReference, merchantReference, amount, fee, settlementId, occurredAt, description,
                    null);
        }

        public Line(String lineId, LineType type, String providerReference, String merchantReference, Money amount,
                    Money fee, String settlementId, Instant occurredAt) {
            this(lineId, type, providerReference, merchantReference, amount, fee, settlementId, occurredAt, null);
        }

        /** What the item was charged in: {@code charged} when given, else the amount. */
        public Money chargedAmount() {
            return charged != null ? charged : amount;
        }
    }

    public record Settlement(String settlementId, long netAmount, String currency, String bankReference,
                             Instant settledAt) {
    }

    public SettlementReport {
        lines = List.copyOf(lines);
        settlements = List.copyOf(settlements);
    }
}
