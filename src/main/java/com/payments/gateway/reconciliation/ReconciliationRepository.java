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
                         Instant startedAt, Instant completedAt, long chargebackAmount, long adjustmentAmount) {
    }

    public record ExceptionRow(String id, String runId, String merchantId, String providerCode, String type,
                               String reference, String entityId, Long expectedAmount, Long actualAmount,
                               String details, String status, String resolution, Instant createdAt,
                               Instant resolvedAt, Instant dueAt, String assignee, Instant assignedAt) {
    }

    /** Filters for the exception queue; null fields do not filter. */
    public record ExceptionFilter(String status, String merchantId, String assignee, Instant overdueAt) {
    }

    /** Exceptions opened by the runs of one window, by type and current state. */
    public record ExceptionTally(String type, String status, boolean overdue, int count) {
    }

    public record Totals(int linesTotal, int linesMatched, int linesAutoHealed, int exceptionsOpened, long grossAmount,
                         long refundAmount, long feeAmount, long settledAmount, long chargebackAmount,
                         long adjustmentAmount) {
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
                       fee_amount = :fees, settled_amount = :settled, chargeback_amount = :chargebacks,
                       adjustment_amount = :adjustments, completed_at = :now
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
                .param("chargebacks", totals.chargebackAmount())
                .param("adjustments", totals.adjustmentAmount())
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
                .query(ReconciliationRepository::mapRun)
                .optional();
    }

    /** The latest run per merchant PSP account for exactly this window (reruns replace earlier results). */
    public List<RunRow> latestRunsForWindow(Instant from, Instant to, String merchantId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (merchant_id, provider_code) *
                  FROM reconciliation_runs
                 WHERE window_start = :from AND window_end = :to
                   AND (CAST(:merchantId AS text) IS NULL OR merchant_id = :merchantId)
                 ORDER BY merchant_id, provider_code, started_at DESC, id DESC
                """)
                .param("from", Sql.ts(from))
                .param("to", Sql.ts(to))
                .param("merchantId", merchantId)
                .query(ReconciliationRepository::mapRun)
                .list();
    }

    private static RunRow mapRun(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RunRow(rs.getString("id"), rs.getString("merchant_id"), rs.getString("provider_code"),
                Sql.instant(rs, "window_start"), Sql.instant(rs, "window_end"), rs.getString("status"),
                rs.getInt("lines_total"), rs.getInt("lines_matched"), rs.getInt("lines_auto_healed"),
                rs.getInt("exceptions_opened"), rs.getLong("gross_amount"), rs.getLong("refund_amount"),
                rs.getLong("fee_amount"), rs.getLong("settled_amount"), rs.getString("error"),
                Sql.instant(rs, "started_at"), Sql.instant(rs, "completed_at"), rs.getLong("chargeback_amount"),
                rs.getLong("adjustment_amount"));
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
                                 String details, Instant now, Instant dueAt) {
        return jdbc.sql("""
                INSERT INTO reconciliation_exceptions (id, run_id, merchant_id, provider_code, type, reference, entity_id,
                                                       expected_amount, actual_amount, details, status, created_at, due_at)
                VALUES (:id, :runId, :merchantId, :provider, :type, :reference, :entityId, :expected, :actual, :details,
                        'OPEN', :now, :dueAt)
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
                .param("dueAt", Sql.ts(dueAt))
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

    /** Assigns an open exception; returns false when it is not open. */
    public boolean assignException(String id, String assignee, Instant now) {
        return jdbc.sql("""
                UPDATE reconciliation_exceptions SET assignee = :assignee, assigned_at = :now
                 WHERE id = :id AND status = 'OPEN'
                """)
                .param("id", id)
                .param("assignee", assignee)
                .param("now", Sql.ts(now))
                .update() == 1;
    }

    public long countOpenExceptions(String merchantId) {
        return jdbc.sql("""
                SELECT count(*) FROM reconciliation_exceptions
                 WHERE status = 'OPEN' AND (CAST(:merchantId AS text) IS NULL OR merchant_id = :merchantId)
                """)
                .param("merchantId", merchantId)
                .query(Long.class)
                .single();
    }

    public long countOverdueExceptions(String merchantId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM reconciliation_exceptions
                 WHERE status = 'OPEN' AND due_at < :now
                   AND (CAST(:merchantId AS text) IS NULL OR merchant_id = :merchantId)
                """)
                .param("merchantId", merchantId)
                .param("now", Sql.ts(now))
                .query(Long.class)
                .single();
    }

    /** Exceptions opened by any run of this window, grouped by type, current status and whether overdue. */
    public List<ExceptionTally> tallyExceptionsForWindow(Instant from, Instant to, String merchantId, Instant now) {
        return jdbc.sql("""
                SELECT e.type, e.status, (e.status = 'OPEN' AND e.due_at < :now) AS overdue, count(*) AS n
                  FROM reconciliation_exceptions e JOIN reconciliation_runs r ON r.id = e.run_id
                 WHERE r.window_start = :from AND r.window_end = :to
                   AND (CAST(:merchantId AS text) IS NULL OR r.merchant_id = :merchantId)
                 GROUP BY 1, 2, 3
                """)
                .param("from", Sql.ts(from))
                .param("to", Sql.ts(to))
                .param("merchantId", merchantId)
                .param("now", Sql.ts(now))
                .query((rs, n) -> new ExceptionTally(rs.getString("type"), rs.getString("status"), rs.getBoolean("overdue"),
                        rs.getInt("n")))
                .list();
    }

    public List<ExceptionRow> exceptionsForRun(String runId) {
        return jdbc.sql("SELECT * FROM reconciliation_exceptions WHERE run_id = :runId ORDER BY created_at, id")
                .param("runId", runId)
                .query(ReconciliationRepository::mapException)
                .list();
    }

    public List<ExceptionRow> exceptions(ExceptionFilter filter, int limit) {
        String sql = "SELECT * FROM reconciliation_exceptions WHERE 1 = 1"
                + (filter.status() == null ? "" : " AND status = :status")
                + (filter.merchantId() == null ? "" : " AND merchant_id = :merchantId")
                + (filter.assignee() == null ? "" : " AND assignee = :assignee")
                + (filter.overdueAt() == null ? "" : " AND status = 'OPEN' AND due_at < :overdueAt")
                + " ORDER BY created_at DESC, id LIMIT :limit";
        var statement = jdbc.sql(sql).param("limit", limit);
        if (filter.status() != null) {
            statement = statement.param("status", filter.status());
        }
        if (filter.merchantId() != null) {
            statement = statement.param("merchantId", filter.merchantId());
        }
        if (filter.assignee() != null) {
            statement = statement.param("assignee", filter.assignee());
        }
        if (filter.overdueAt() != null) {
            statement = statement.param("overdueAt", Sql.ts(filter.overdueAt()));
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
                Sql.instant(rs, "resolved_at"), Sql.instant(rs, "due_at"), rs.getString("assignee"),
                Sql.instant(rs, "assigned_at"));
    }
}
