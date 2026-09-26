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
     * disputed attempt's id.
     */
    public enum LineType {
        PAYMENT,
        REFUND,
        CHARGEBACK,
        CHARGEBACK_REVERSAL
    }

    public record Line(String lineId, LineType type, String providerReference, String merchantReference, Money amount,
                       Money fee, String settlementId, Instant occurredAt) {
    }

    public record Settlement(String settlementId, long netAmount, String currency, String bankReference,
                             Instant settledAt) {
    }

    public SettlementReport {
        lines = List.copyOf(lines);
        settlements = List.copyOf(settlements);
    }
}
