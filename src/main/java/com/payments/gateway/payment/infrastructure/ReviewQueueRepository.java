package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.shared.jdbc.Sql;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read side of the manual review queue (ADR-016); served by the partial {@code WHERE needs_review} indexes. */
@Repository
public class ReviewQueueRepository {

    public record Row(String kind, String id, String paymentId, String merchantId, String providerCode, String status,
                      long amount, String currency, String reasons, String riskReasons, Instant flaggedAt) {
    }

    private static final String ATTEMPTS = """
            SELECT 'attempt' AS kind, id, payment_id, merchant_id, provider_code, status, amount, currency,
                   review_reason, risk_reasons, flagged_at
              FROM payment_attempts
             WHERE needs_review AND (CAST(:merchantId AS text) IS NULL OR merchant_id = :merchantId)
            """;

    private static final String REFUNDS = """
            SELECT 'refund' AS kind, id, payment_id, merchant_id, provider_code, status, amount, currency,
                   review_reason, NULL::text AS risk_reasons, flagged_at
              FROM refunds
             WHERE needs_review AND (CAST(:merchantId AS text) IS NULL OR merchant_id = :merchantId)
            """;

    private static final String DISPUTES = """
            SELECT 'dispute' AS kind, id, payment_id, merchant_id, provider_code, status, amount, currency,
                   review_reason, NULL::text AS risk_reasons, flagged_at
              FROM disputes
             WHERE needs_review AND (CAST(:merchantId AS text) IS NULL OR merchant_id = :merchantId)
            """;

    private static final String CREDITS = """
            SELECT 'credit' AS kind, id, payment_id, merchant_id, provider_code,
                   CASE WHEN attempt_id IS NULL THEN 'UNMATCHED' ELSE 'RECEIVED' END AS status, amount, currency,
                   review_reason, NULL::text AS risk_reasons, flagged_at
              FROM transfer_credits
             WHERE needs_review AND (CAST(:merchantId AS text) IS NULL OR merchant_id = :merchantId)
            """;

    private final JdbcClient jdbc;

    public ReviewQueueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Oldest first; {@code kind} is {@code attempt}, {@code refund}, {@code dispute}, {@code credit} or null for all. */
    public List<Row> open(String kind, String merchantId, int limit) {
        String source = kind == null
                ? ATTEMPTS + " UNION ALL " + REFUNDS + " UNION ALL " + DISPUTES + " UNION ALL " + CREDITS
                : switch (kind) {
                    case "attempt" -> ATTEMPTS;
                    case "refund" -> REFUNDS;
                    case "credit" -> CREDITS;
                    default -> DISPUTES;
                };
        return jdbc.sql(source + " ORDER BY flagged_at, id LIMIT :limit")
                .param("merchantId", merchantId)
                .param("limit", limit)
                .query((rs, n) -> new Row(rs.getString("kind"), rs.getString("id"), rs.getString("payment_id"),
                        rs.getString("merchant_id"), rs.getString("provider_code"), rs.getString("status"),
                        rs.getLong("amount"), rs.getString("currency"), rs.getString("review_reason"),
                        rs.getString("risk_reasons"), Sql.instant(rs, "flagged_at")))
                .list();
    }

    public long countOpenAttempts() {
        return jdbc.sql("SELECT count(*) FROM payment_attempts WHERE needs_review").query(Long.class).single();
    }

    public long countOpenRefunds() {
        return jdbc.sql("SELECT count(*) FROM refunds WHERE needs_review").query(Long.class).single();
    }

    public long countOpenDisputes() {
        return jdbc.sql("SELECT count(*) FROM disputes WHERE needs_review").query(Long.class).single();
    }

    public long countOpenCredits() {
        return jdbc.sql("SELECT count(*) FROM transfer_credits WHERE needs_review").query(Long.class).single();
    }
}
