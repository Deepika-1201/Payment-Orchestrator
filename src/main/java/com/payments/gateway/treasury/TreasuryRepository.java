package com.payments.gateway.treasury;

import com.payments.gateway.shared.Ids;
import com.payments.gateway.ledger.LedgerAccountType;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.shared.events.CurrencyConversion;
import com.payments.gateway.shared.events.FundsMovement;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.treasury.TreasuryAmounts.Kind;
import com.payments.gateway.treasury.TreasuryModel.Balance;
import com.payments.gateway.treasury.TreasuryModel.Policy;
import com.payments.gateway.treasury.TreasuryModel.Source;
import com.payments.gateway.treasury.TreasuryModel.PspFee;
import com.payments.gateway.treasury.TreasuryModel.Receipt;
import com.payments.gateway.treasury.TreasuryModel.Batch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.EnumMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TreasuryRepository {
    public record StoredMovement(FundsMovement movement, Instant eligibleAt) {
    }

    private final JdbcClient jdbc;
    private final LedgerService ledger;

    public TreasuryRepository(JdbcClient jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    public void lock() {
        if (jdbc.sql("UPDATE treasury_control SET version = version + 1 WHERE id = 1").update() != 1) {
            throw new IllegalStateException("Escrow control row is missing");
        }
    }

    public boolean movement(FundsMovement movement, Instant eligibleAt) {
        return jdbc.sql("""
                INSERT INTO treasury_movements (kind, reference_id, merchant_id, provider_code, payment_id, amount,
                                                currency, merchant_entitled, occurred_at, eligible_at)
                VALUES (:kind, :reference, :merchant, :provider, :payment, :amount, :currency, :entitled, :now, :eligible)
                ON CONFLICT (kind, reference_id) DO NOTHING
                """).param("kind", movement.type().name()).param("reference", movement.referenceId())
                .param("merchant", movement.merchantId()).param("provider", movement.providerCode()).param("payment", movement.paymentId())
                .param("amount", movement.amount().amount()).param("currency", movement.amount().currency())
                .param("entitled", movement.merchantEntitled()).param("now", Sql.ts(movement.occurredAt()))
                .param("eligible", Sql.ts(eligibleAt)).update() == 1;
    }

    public Optional<StoredMovement> movement(String kind, String referenceId) {
        return jdbc.sql("SELECT * FROM treasury_movements WHERE kind = :kind AND reference_id = :reference")
                .param("kind", kind).param("reference", referenceId)
                .query((row, index) -> new StoredMovement(new FundsMovement(FundsMovement.Type.valueOf(row.getString("kind")),
                        row.getString("merchant_id"), row.getString("provider_code"), row.getString("reference_id"),
                        row.getString("payment_id"), new Money(row.getLong("amount"), row.getString("currency")),
                        Sql.instant(row, "occurred_at"), row.getBoolean("merchant_entitled")), Sql.instant(row, "eligible_at")))
                .optional();
    }

    public boolean conversion(CurrencyConversion conversion) {
        return jdbc.sql("""
                INSERT INTO treasury_conversions (id, kind, reference_id, merchant_id, provider_code, amount, occurred_at)
                VALUES (:id, :kind, :reference, :merchant, :provider, :amount, :now)
                ON CONFLICT (kind, reference_id) DO NOTHING
                """).param("id", conversion.id()).param("kind", conversion.kind().name()).param("reference", conversion.referenceId())
                .param("merchant", conversion.merchantId()).param("provider", conversion.providerCode())
                .param("amount", conversion.settled().amount()).param("now", Sql.ts(conversion.occurredAt())).update() == 1;
    }

    public Optional<CurrencyConversion> conversion(String kind, String referenceId) {
        return jdbc.sql("SELECT * FROM treasury_conversions WHERE kind = :kind AND reference_id = :reference")
                .param("kind", kind).param("reference", referenceId)
                .query((row, index) -> new CurrencyConversion(row.getString("id"), CurrencyConversion.Kind.valueOf(row.getString("kind")),
                        row.getString("merchant_id"), row.getString("provider_code"), Money.of(row.getLong("amount"), "INR"),
                        0, Sql.instant(row, "occurred_at"), row.getString("reference_id"))).optional();
    }

    public Optional<Policy> policy(String merchantId) {
        return jdbc.sql("SELECT * FROM settlement_policies WHERE merchant_id = :id ORDER BY created_at DESC, id DESC LIMIT 1")
                .param("id", merchantId).query(TreasuryRepository::policy).optional();
    }

    public void policy(Policy policy) {
        jdbc.sql("""
                INSERT INTO settlement_policies (id, merchant_id, kyc_id, business_days, reserve_target, held,
                                                 bank_registration_reference, reason, created_by, created_at)
                VALUES (:id, :merchant, :kyc, :days, :reserve, :held, :bank, :reason, :actor, :now)
                """).param("id", policy.id()).param("merchant", policy.merchantId()).param("kyc", policy.kycId())
                .param("days", policy.businessDays()).param("reserve", policy.reserveTarget()).param("held", policy.held())
                .param("bank", policy.bankRegistrationReference()).param("reason", policy.reason()).param("actor", policy.createdBy())
                .param("now", Sql.ts(policy.createdAt())).update();
    }

    public Source source(String merchantId, String provider, Kind kind, String reference, long amount, long tax,
                         Instant occurredAt, Instant eligibleAt) {
        long delta = new TreasuryAmounts.Line(kind, amount, tax).payableDelta();
        String id = Ids.newId("tsrc");
        jdbc.sql("""
                INSERT INTO treasury_sources (id, merchant_id, provider_code, kind, reference_id, amount, tax, payable_delta,
                                               occurred_at, eligible_at)
                VALUES (:id, :merchant, :provider, :kind, :reference, :amount, :tax, :delta, :now, :eligible)
                """).param("id", id).param("merchant", merchantId).param("provider", provider).param("kind", kind.name())
                .param("reference", reference).param("amount", amount).param("tax", tax).param("delta", delta)
                .param("now", Sql.ts(occurredAt)).param("eligible", Sql.ts(eligibleAt)).update();
        return new Source(id, merchantId, provider, kind, reference, amount, tax, delta, occurredAt, eligibleAt);
    }

    public List<Source> sources(String merchantId) {
        return jdbc.sql("SELECT * FROM treasury_sources WHERE merchant_id = :id ORDER BY occurred_at, id")
                .param("id", merchantId).query(TreasuryRepository::source).list();
    }

    public Balance balance(String merchantId) {
        Map<LedgerAccountType, Long> amounts = new EnumMap<>(LedgerAccountType.class);
        ledger.bookBalances(TreasuryService.BOOK, merchantId).stream().filter(row -> "INR".equals(row.currency()))
            .forEach(row -> amounts.merge(row.account(), row.balance(), Math::addExact));
        return new Balance(amounts.getOrDefault(LedgerAccountType.ESCROW, 0L), amounts.getOrDefault(LedgerAccountType.PSP_RECEIVABLE, 0L),
            amounts.getOrDefault(LedgerAccountType.MERCHANT_PAYABLE, 0L), amounts.getOrDefault(LedgerAccountType.RESERVE, 0L),
            amounts.getOrDefault(LedgerAccountType.SETTLEMENT_PAYABLE, 0L), amounts.getOrDefault(LedgerAccountType.CUSTOMER_FUNDS, 0L),
            amounts.getOrDefault(LedgerAccountType.PLATFORM_FEE_INCOME, 0L), amounts.getOrDefault(LedgerAccountType.GST_PAYABLE, 0L));
    }

            public Optional<PspFee> fee(String reference) {
            return jdbc.sql("SELECT * FROM treasury_psp_fees WHERE reference_id = :reference").param("reference", reference)
                .query((row, index) -> new PspFee(row.getString("reference_id"), row.getString("merchant_id"), row.getString("provider_code"),
                    row.getLong("amount"), row.getLong("merchant_amount"), Sql.instant(row, "occurred_at"))).optional();
            }

            public void fee(PspFee fee) {
            jdbc.sql("""
                INSERT INTO treasury_psp_fees (reference_id, merchant_id, provider_code, amount, merchant_amount, occurred_at)
                VALUES (:reference, :merchant, :provider, :amount, :share, :now)
                """).param("reference", fee.referenceId()).param("merchant", fee.merchantId()).param("provider", fee.providerCode())
                .param("amount", fee.amount()).param("share", fee.merchantAmount()).param("now", Sql.ts(fee.occurredAt())).update();
            }

            public Optional<Receipt> receipt(String ownerId, String provider, String settlementId) {
            return jdbc.sql("SELECT * FROM escrow_receipts WHERE owner_id = :owner AND provider_code = :provider AND settlement_id = :settlement")
                .param("owner", ownerId).param("provider", provider).param("settlement", settlementId)
                .query((row, index) -> new Receipt(row.getString("id"), ownerId, provider, settlementId, row.getLong("amount"),
                    row.getString("bank_reference"), Sql.instant(row, "settled_at"), Map.of()))
                .optional().map(receipt -> {
                    Map<String, Long> allocations = new HashMap<>();
                    jdbc.sql("SELECT merchant_id, amount FROM escrow_receipt_allocations WHERE receipt_id = :id")
                        .param("id", receipt.id()).query((row, index) -> {
                        allocations.put(row.getString("merchant_id"), row.getLong("amount"));
                        return row.getString("merchant_id");
                        }).list();
                    return new Receipt(receipt.id(), ownerId, provider, settlementId, receipt.amount(), receipt.bankReference(),
                        receipt.settledAt(), allocations);
                });
            }

            public void receipt(Receipt receipt) {
            jdbc.sql("""
                INSERT INTO escrow_receipts (id, owner_id, provider_code, settlement_id, amount, bank_reference, settled_at)
                VALUES (:id, :owner, :provider, :settlement, :amount, :bank, :now)
                """).param("id", receipt.id()).param("owner", receipt.ownerId()).param("provider", receipt.providerCode())
                .param("settlement", receipt.settlementId()).param("amount", receipt.amount()).param("bank", receipt.bankReference())
                .param("now", Sql.ts(receipt.settledAt())).update();
            receipt.allocations().forEach((merchant, amount) -> jdbc.sql("""
                INSERT INTO escrow_receipt_allocations (receipt_id, merchant_id, amount) VALUES (:id, :merchant, :amount)
                """).param("id", receipt.id()).param("merchant", merchant).param("amount", amount).update());
            }

            public List<Source> unclaimed(String merchantId, Instant now) {
            return jdbc.sql("""
                SELECT source.* FROM treasury_sources source
                 WHERE merchant_id = :merchant AND eligible_at <= :now
                   AND NOT EXISTS (SELECT 1 FROM settlement_source_claims claim WHERE claim.source_id = source.id)
                 ORDER BY occurred_at, id LIMIT 10001
                """).param("merchant", merchantId).param("now", Sql.ts(now)).query(TreasuryRepository::source).list();
            }

            public long unclaimedNet(String merchantId, Instant now) {
            return jdbc.sql("""
                SELECT coalesce(sum(payable_delta), 0) FROM treasury_sources source
                 WHERE merchant_id = :merchant AND eligible_at <= :now
                   AND NOT EXISTS (SELECT 1 FROM settlement_source_claims claim WHERE claim.source_id = source.id)
                """).param("merchant", merchantId).param("now", Sql.ts(now)).query(Long.class).single();
            }

            public long protectedCash(String merchantId) {
                long protectedAmount = 0;
                for (var row : ledger.bookBalances(TreasuryService.BOOK, merchantId)) {
                    if (!"INR".equals(row.currency())) {
                        continue;
                    }
                    long amount = switch (row.account()) {
                        case CUSTOMER_FUNDS, RESERVE, SETTLEMENT_PAYABLE, PLATFORM_FEE_INCOME, GST_PAYABLE -> Math.max(0, row.balance());
                        case PSP_RECEIVABLE -> Math.max(0, Math.negateExact(row.balance()));
                        default -> 0;
                    };
                    protectedAmount = Math.addExact(protectedAmount, amount);
                }
                return protectedAmount;
            }

            public Optional<Batch> batch(String merchantId, String id) {
            return jdbc.sql("SELECT * FROM settlement_batches WHERE merchant_id = :merchant AND id = :id")
                .param("merchant", merchantId).param("id", id).query(TreasuryRepository::batch).optional().map(this::withItems);
            }

            public Optional<Batch> batchByReference(String merchantId, String reference) {
            return jdbc.sql("SELECT * FROM settlement_batches WHERE merchant_id = :merchant AND reference = :reference")
                .param("merchant", merchantId).param("reference", reference).query(TreasuryRepository::batch).optional().map(this::withItems);
            }

            public List<Batch> batches(String merchantId) {
            return jdbc.sql("SELECT * FROM settlement_batches WHERE merchant_id = :merchant ORDER BY created_at DESC, id DESC LIMIT 100")
                .param("merchant", merchantId).query(TreasuryRepository::batch).list();
            }

            public boolean hasDraft(String merchantId) {
            return jdbc.sql("SELECT EXISTS (SELECT 1 FROM settlement_batches WHERE merchant_id = :merchant AND status = 'DRAFT')")
                .param("merchant", merchantId).query(Boolean.class).single();
            }

            public void batch(Batch batch) {
            var totals = batch.totals();
            jdbc.sql("""
                INSERT INTO settlement_batches (id, merchant_id, reference, policy_id, kyc_id, status, captures, refunds,
                    chargebacks, psp_fees, service_fees, gst, reserve_change, released_payouts, amount, created_by, created_at)
                VALUES (:id, :merchant, :reference, :policy, :kyc, 'DRAFT', :captures, :refunds, :chargebacks, :fees,
                    :service, :gst, :reserve, :released, :amount, :actor, :now)
                """).param("id", batch.id()).param("merchant", batch.merchantId()).param("reference", batch.reference())
                .param("policy", batch.policyId()).param("kyc", batch.kycId()).param("captures", totals.captures())
                .param("refunds", totals.refunds()).param("chargebacks", totals.chargebacks()).param("fees", totals.pspFees())
                .param("service", totals.serviceFees()).param("gst", totals.gst()).param("reserve", totals.reserveChange())
                .param("released", totals.releasedPayouts()).param("amount", batch.amount()).param("actor", batch.createdBy())
                .param("now", Sql.ts(batch.createdAt())).update();
            for (Source source : batch.items()) {
                jdbc.sql("INSERT INTO settlement_batch_items (batch_id, source_id, merchant_id) VALUES (:batch, :source, :merchant)")
                    .param("batch", batch.id()).param("source", source.id()).param("merchant", batch.merchantId()).update();
                jdbc.sql("INSERT INTO settlement_source_claims (source_id, batch_id) VALUES (:source, :batch)")
                    .param("source", source.id()).param("batch", batch.id()).update();
            }
            }

            public void decide(Batch batch, String decision, String actor, String reason, Instant now) {
            jdbc.sql("""
                UPDATE settlement_batches SET status = :decision, decided_by = :actor, decided_at = :now, decision_reason = :reason
                 WHERE id = :id AND status = 'DRAFT'
                """).param("decision", decision).param("actor", actor).param("now", Sql.ts(now)).param("reason", reason)
                .param("id", batch.id()).update();
            if ("REJECTED".equals(decision)) {
                jdbc.sql("DELETE FROM settlement_source_claims WHERE batch_id = :id").param("id", batch.id()).update();
            }
            }

            public List<String> merchantsAfter(String after, int limit, Instant now) {
            return jdbc.sql("""
                SELECT merchant.id FROM merchants merchant WHERE merchant.mode = 'AGGREGATOR' AND merchant.id > :after
                                    AND (EXISTS (SELECT 1 FROM treasury_sources source WHERE source.merchant_id = merchant.id AND source.eligible_at <= :now
                                        AND NOT EXISTS (SELECT 1 FROM settlement_source_claims claim WHERE claim.source_id = source.id))
                                        OR coalesce((SELECT sum(CASE WHEN source.kind = 'RESERVE_HELD' THEN source.amount ELSE -source.amount END)
                                                                    FROM treasury_sources source WHERE source.merchant_id = merchant.id
                                                                     AND source.kind IN ('RESERVE_HELD', 'RESERVE_RELEASED')), 0)
                                             > coalesce((SELECT policy.reserve_target FROM settlement_policies policy WHERE policy.merchant_id = merchant.id
                                                                        ORDER BY policy.created_at DESC, policy.id DESC LIMIT 1), 0))
                  AND NOT EXISTS (SELECT 1 FROM settlement_batches batch WHERE batch.merchant_id = merchant.id AND batch.status = 'DRAFT')
                 ORDER BY merchant.id LIMIT :limit
                """).param("after", after).param("limit", limit).param("now", Sql.ts(now)).query(String.class).list();
            }

            private Batch withItems(Batch batch) {
            List<Source> items = jdbc.sql("""
                SELECT source.* FROM settlement_batch_items item JOIN treasury_sources source ON source.id = item.source_id
                 WHERE item.batch_id = :id ORDER BY source.occurred_at, source.id
                """).param("id", batch.id()).query(TreasuryRepository::source).list();
            return new Batch(batch.id(), batch.merchantId(), batch.reference(), batch.policyId(), batch.kycId(), batch.status(), batch.totals(),
                batch.amount(), batch.createdBy(), batch.createdAt(), batch.decidedBy(), batch.decidedAt(), batch.decisionReason(), items);
            }

            static Batch batch(ResultSet row, int index) throws SQLException {
            return new Batch(row.getString("id"), row.getString("merchant_id"), row.getString("reference"), row.getString("policy_id"),
                row.getString("kyc_id"), row.getString("status"), new TreasuryAmounts.Totals(row.getLong("captures"), row.getLong("refunds"),
                row.getLong("chargebacks"), row.getLong("psp_fees"), row.getLong("service_fees"), row.getLong("gst"),
                row.getLong("reserve_change"), row.getLong("released_payouts")), row.getLong("amount"), row.getString("created_by"),
                Sql.instant(row, "created_at"), row.getString("decided_by"), Sql.instant(row, "decided_at"), row.getString("decision_reason"), List.of());
            }

    static Policy policy(ResultSet row, int index) throws SQLException {
        return new Policy(row.getString("id"), row.getString("merchant_id"), row.getString("kyc_id"), row.getInt("business_days"),
                row.getLong("reserve_target"), row.getBoolean("held"), row.getString("bank_registration_reference"),
                row.getString("reason"), row.getString("created_by"), Sql.instant(row, "created_at"));
    }

    static Source source(ResultSet row, int index) throws SQLException {
        return new Source(row.getString("id"), row.getString("merchant_id"), row.getString("provider_code"),
                Kind.valueOf(row.getString("kind")), row.getString("reference_id"), row.getLong("amount"), row.getLong("tax"),
                row.getLong("payable_delta"), Sql.instant(row, "occurred_at"), Sql.instant(row, "eligible_at"));
    }
}