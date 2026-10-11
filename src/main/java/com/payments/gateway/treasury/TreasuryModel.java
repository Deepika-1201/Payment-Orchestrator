package com.payments.gateway.treasury;

import com.payments.gateway.treasury.TreasuryAmounts.Kind;
import com.payments.gateway.treasury.TreasuryAmounts.Line;
import java.time.Instant;
import java.util.Map;
import java.util.List;

public final class TreasuryModel {
    private TreasuryModel() {
    }

    public record Policy(String id, String merchantId, String kycId, int businessDays, long reserveTarget, boolean held,
                         String bankRegistrationReference, String reason, String createdBy, Instant createdAt) {
    }

    public record Source(String id, String merchantId, String providerCode, Kind kind, String referenceId, long amount,
                         long tax, long payableDelta, Instant occurredAt, Instant eligibleAt) {
        public Line line() {
            return new Line(kind, amount, tax);
        }
    }

    public record PspFee(String referenceId, String merchantId, String providerCode, long amount, long merchantAmount,
                         Instant occurredAt) {
    }

    public record Receipt(String id, String ownerId, String providerCode, String settlementId, long amount,
                          String bankReference, Instant settledAt, Map<String, Long> allocations) {
        public Receipt {
            allocations = Map.copyOf(allocations);
        }
    }

    public record Batch(String id, String merchantId, String reference, String policyId, String kycId, String status,
                        TreasuryAmounts.Totals totals, long amount, String createdBy, Instant createdAt,
                        String decidedBy, Instant decidedAt, String decisionReason, List<Source> items) {
        public Batch {
            items = List.copyOf(items);
        }
    }

    public record Balance(long escrow, long pspReceivable, long merchantPayable, long reserve, long settlementPayable,
                          long customerFunds, long platformFeeIncome, long gstPayable) {
        @com.fasterxml.jackson.annotation.JsonProperty("difference")
        public long difference() {
            long assets = Math.addExact(escrow, pspReceivable);
            long claims = Math.addExact(merchantPayable, reserve);
            claims = Math.addExact(claims, settlementPayable);
            claims = Math.addExact(claims, customerFunds);
            claims = Math.addExact(claims, platformFeeIncome);
            claims = Math.addExact(claims, gstPayable);
            return Math.subtractExact(assets, claims);
        }
    }
}