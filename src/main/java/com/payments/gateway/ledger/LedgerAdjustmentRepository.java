package com.payments.gateway.ledger;

import com.payments.gateway.shared.jdbc.Sql;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerAdjustmentRepository {

    public record AdjustmentRow(String id, String merchantId, String providerCode, LedgerAccountType debitAccount,
                                LedgerAccountType creditAccount, long amount, String currency, String reason,
                                String reference, String status, String requestedBy, Instant requestedAt,
                                Instant expiresAt, String decidedBy, Instant decidedAt, String decisionNote) {
    }

    private final JdbcClient jdbc;

    public LedgerAdjustmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AdjustmentRow row) {
        jdbc.sql("""
                INSERT INTO ledger_adjustments (id, merchant_id, provider_code, debit_account, credit_account, amount,
                                                currency, reason, reference, status, requested_by, requested_at, expires_at)
                VALUES (:id, :merchantId, :provider, :debit, :credit, :amount, :currency, :reason, :reference, 'PENDING',
                        :requestedBy, :requestedAt, :expiresAt)
                """)
                .param("id", row.id())
                .param("merchantId", row.merchantId())
                .param("provider", row.providerCode())
                .param("debit", row.debitAccount().name())
                .param("credit", row.creditAccount().name())
                .param("amount", row.amount())
                .param("currency", row.currency())
                .param("reason", row.reason())
                .param("reference", row.reference())
                .param("requestedBy", row.requestedBy())
                .param("requestedAt", Sql.ts(row.requestedAt()))
                .param("expiresAt", Sql.ts(row.expiresAt()))
                .update();
    }

    public Optional<AdjustmentRow> lock(String id) {
        return jdbc.sql("SELECT * FROM ledger_adjustments WHERE id = :id FOR UPDATE").param("id", id)
                .query(LedgerAdjustmentRepository::map).optional();
    }

    public Optional<AdjustmentRow> find(String id) {
        return jdbc.sql("SELECT * FROM ledger_adjustments WHERE id = :id").param("id", id)
                .query(LedgerAdjustmentRepository::map).optional();
    }

    public List<AdjustmentRow> list(String status, String merchantId, int limit) {
        return jdbc.sql("""
                SELECT * FROM ledger_adjustments
                 WHERE (CAST(:status AS text) IS NULL OR status = :status)
                   AND (CAST(:merchantId AS text) IS NULL OR merchant_id = :merchantId)
                 ORDER BY requested_at DESC, id LIMIT :limit
                """)
                .param("status", status)
                .param("merchantId", merchantId)
                .param("limit", limit)
                .query(LedgerAdjustmentRepository::map)
                .list();
    }

    public void decide(String id, String status, String decidedBy, String note, Instant now) {
        jdbc.sql("""
                UPDATE ledger_adjustments SET status = :status, decided_by = :decidedBy, decision_note = :note,
                                              decided_at = :now
                 WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", id)
                .param("status", status)
                .param("decidedBy", decidedBy)
                .param("note", note)
                .param("now", Sql.ts(now))
                .update();
    }

    private static AdjustmentRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AdjustmentRow(rs.getString("id"), rs.getString("merchant_id"), rs.getString("provider_code"),
                LedgerAccountType.valueOf(rs.getString("debit_account")),
                LedgerAccountType.valueOf(rs.getString("credit_account")), rs.getLong("amount"), rs.getString("currency"),
                rs.getString("reason"), rs.getString("reference"), rs.getString("status"), rs.getString("requested_by"),
                Sql.instant(rs, "requested_at"), Sql.instant(rs, "expires_at"), rs.getString("decided_by"),
                Sql.instant(rs, "decided_at"), rs.getString("decision_note"));
    }
}
