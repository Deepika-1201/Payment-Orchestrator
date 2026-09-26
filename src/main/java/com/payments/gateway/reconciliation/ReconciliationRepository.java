package com.payments.gateway.reconciliation;

import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.shared.jdbc.Sql;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationRepository {

    public record RunRow(String id, String merchantId, String providerCode, Instant windowStart, Instant windowEnd,
                         String status, int linesTotal, int linesMatched, int linesAutoHealed, int exceptionsOpened,
                         long grossAmount, long refundAmount, long feeAmount, long settledAmount, String error,
                         Instant startedAt, Instant completedAt) {
    }

    public record ExceptionRow(String id, String runId, String merchantId, String providerCode, String type,
                               String reference, String entityId, Long expectedAmount, Long actualAmount,
                               String details, String status, String resolution, Instant createdAt,
                               Instant resolvedAt) {
    }

    public record Totals(int linesTotal, int linesMatched, int linesAutoHealed, int exceptionsOpened, long grossAmount,
                         long refundAmount, long feeAmount, long settledAmount) {
    }

    private static final int IN_CHUNK = 500;

    private final JdbcClient jdbc;

    public ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertRun(String id, String merchantId, String providerCode, Instant from, Instant to, Instant now) {
        jdbc.sql("""
                INSERT INTO reconciliation_runs (id, merchant_id, provider_code, window_start, window_end, status, started_at)
                VALUES (:id, :merchantId, :provider, :from, :to, 'RUNNING', :now)
                """)
                .param("id", id)
                .param("merchantId", merchantId)
                .param("provider", providerCode)
                .param("from", Sql.ts(from))
                .param("to", Sql.ts(to))
                .param("now", Sql.ts(now))
                .update();
    }

    public void completeRun(String id, Totals totals, Instant now) {
        jdbc.sql("""
                UPDATE reconciliation_runs
                   SET status = 'COMPLETED', lines_total = :total, lines_matched = :matched, lines_auto_healed = :healed,
                       exceptions_opened = :exceptions, gross_amount = :gross, refund_amount = :refunds,
                       fee_amount = :fees, settled_amount = :settled, completed_at = :now
                 WHERE id = :id
                """)
                .param("id", id)
                .param("total", totals.linesTotal())
                .param("matched", totals.linesMatched())
                .param("healed", totals.linesAutoHealed())
                .param("exceptions", totals.exceptionsOpened())
                .param("gross", totals.grossAmount())
                .param("refunds", totals.refundAmount())
                .param("fees", totals.feeAmount())
                .param("settled", totals.settledAmount())
                .param("now", Sql.ts(now))
                .update();
    }

    public void failRun(String id, String error, Instant now) {
        jdbc.sql("UPDATE reconciliation_runs SET status = 'FAILED', error = :error, completed_at = :now WHERE id = :id")
                .param("id", id)
                .param("error", error == null ? "unknown error" : error.substring(0, Math.min(error.length(), 1000)))
                .param("now", Sql.ts(now))
                .update();
    }

    public Optional<RunRow> findRun(String id) {
        return jdbc.sql("SELECT * FROM reconciliation_runs WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new RunRow(rs.getString("id"), rs.getString("merchant_id"), rs.getString("provider_code"),
                        Sql.instant(rs, "window_start"), Sql.instant(rs, "window_end"), rs.getString("status"),
                        rs.getInt("lines_total"), rs.getInt("lines_matched"), rs.getInt("lines_auto_healed"),
                        rs.getInt("exceptions_opened"), rs.getLong("gross_amount"), rs.getLong("refund_amount"),
                        rs.getLong("fee_amount"), rs.getLong("settled_amount"), rs.getString("error"),
                        Sql.instant(rs, "started_at"), Sql.instant(rs, "completed_at")))
                .optional();
    }

    public void insertLine(String runId, String providerCode, SettlementReport.Line line, String result, String entityId) {
        jdbc.sql("""
                INSERT INTO reconciliation_lines (run_id, provider_code, provider_line_id, line_type, provider_reference,
                                                  merchant_reference, amount, fee, currency, settlement_id, occurred_at,
                                                  result, entity_id)
                VALUES (:runId, :provider, :lineId, :type, :providerReference, :merchantReference, :amount, :fee, :currency,
                        :settlementId, :occurredAt, :result, :entityId)
                ON CONFLICT (run_id, provider_line_id) DO NOTHING
                """)
                .param("runId", runId)
                .param("provider", providerCode)
                .param("lineId", line.lineId())
                .param("type", line.type().name())
                .param("providerReference", line.providerReference())
                .param("merchantReference", line.merchantReference())
                .param("amount", line.amount().amount())
                .param("fee", line.fee() == null ? 0 : line.fee().amount())
                .param("currency", line.amount().currency())
                .param("settlementId", line.settlementId())
                .param("occurredAt", Sql.ts(line.occurredAt()))
                .param("result", result)
                .param("entityId", entityId)
                .update();
    }

    /** Returns true when a new exception was opened (an identical open exception is not duplicated). */
    public boolean openException(String id, String runId, String merchantId, String providerCode, String type,
                                 String reference, String entityId, Long expectedAmount, Long actualAmount,
                                 String details, Instant now) {
        return jdbc.sql("""
                INSERT INTO reconciliation_exceptions (id, run_id, merchant_id, provider_code, type, reference, entity_id,
                                                       expected_amount, actual_amount, details, status, created_at)
                VALUES (:id, :runId, :merchantId, :provider, :type, :reference, :entityId, :expected, :actual, :details,
                        'OPEN', :now)
                ON CONFLICT (merchant_id, provider_code, type, reference) WHERE status = 'OPEN' DO NOTHING
                """)
                .param("id", id)
                .param("runId", runId)
                .param("merchantId", merchantId)
                .param("provider", providerCode)
                .param("type", type)
                .param("reference", reference)
                .param("entityId", entityId)
                .param("expected", expectedAmount)
                .param("actual", actualAmount)
                .param("details", details)
                .param("now", Sql.ts(now))
                .update() == 1;
    }

    public int autoResolveMissingAtProvider(String merchantId, String providerCode, String entityId, String runId, Instant now) {
        return jdbc.sql("""
                UPDATE reconciliation_exceptions
                   SET status = 'RESOLVED', resolution = :resolution, resolved_at = :now
                 WHERE merchant_id = :merchantId AND provider_code = :provider AND type = 'MISSING_AT_PROVIDER'
                   AND entity_id = :entityId AND status = 'OPEN'
                """)
                .param("resolution", "auto-resolved: matched by run " + runId)
                .param("now", Sql.ts(now))
                .param("merchantId", merchantId)
                .param("provider", providerCode)
                .param("entityId", entityId)
                .update();
    }

    public boolean resolveException(String id, String resolution, Instant now) {
        return jdbc.sql("""
                UPDATE reconciliation_exceptions SET status = 'RESOLVED', resolution = :resolution, resolved_at = :now
                 WHERE id = :id AND status = 'OPEN'
                """)
                .param("id", id)
                .param("resolution", resolution)
                .param("now", Sql.ts(now))
                .update() == 1;
    }

    public Optional<ExceptionRow> findException(String id) {
        return jdbc.sql("SELECT * FROM reconciliation_exceptions WHERE id = :id").param("id", id)
                .query(ReconciliationRepository::mapException).optional();
    }

    public List<ExceptionRow> exceptionsForRun(String runId) {
        return jdbc.sql("SELECT * FROM reconciliation_exceptions WHERE run_id = :runId ORDER BY created_at, id")
                .param("runId", runId)
                .query(ReconciliationRepository::mapException)
                .list();
    }

    public List<ExceptionRow> exceptions(String status, String merchantId, int limit) {
        String sql = "SELECT * FROM reconciliation_exceptions WHERE 1 = 1"
                + (status == null ? "" : " AND status = :status")
                + (merchantId == null ? "" : " AND merchant_id = :merchantId")
                + " ORDER BY created_at DESC, id LIMIT :limit";
        var statement = jdbc.sql(sql).param("limit", limit);
        if (status != null) {
            statement = statement.param("status", status);
        }
        if (merchantId != null) {
            statement = statement.param("merchantId", merchantId);
        }
        return statement.query(ReconciliationRepository::mapException).list();
    }

    /** Entity ids that appeared in any settlement report of this merchant PSP account, whatever the match result. */
    public Set<String> entitiesSeenInReports(String merchantId, String providerCode, Collection<String> entityIds) {
        Set<String> result = new HashSet<>();
        List<String> ids = new ArrayList<>(entityIds);
        for (int start = 0; start < ids.size(); start += IN_CHUNK) {
            result.addAll(jdbc.sql("""
                    SELECT DISTINCT l.entity_id
                      FROM reconciliation_lines l JOIN reconciliation_runs r ON r.id = l.run_id
                     WHERE r.merchant_id = :merchantId AND l.provider_code = :provider AND l.entity_id IN (:ids)
                    """)
                    .param("merchantId", merchantId)
                    .param("provider", providerCode)
                    .param("ids", ids.subList(start, Math.min(ids.size(), start + IN_CHUNK)))
                    .query(String.class)
                    .list());
        }
        return result;
    }

    private static ExceptionRow mapException(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExceptionRow(rs.getString("id"), rs.getString("run_id"), rs.getString("merchant_id"),
                rs.getString("provider_code"), rs.getString("type"), rs.getString("reference"), rs.getString("entity_id"),
                Sql.nullableLong(rs, "expected_amount"), Sql.nullableLong(rs, "actual_amount"), rs.getString("details"),
                rs.getString("status"), rs.getString("resolution"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "resolved_at"));
    }
}
