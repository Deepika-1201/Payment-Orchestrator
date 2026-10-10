package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.payment.domain.Dispute;
import com.payments.gateway.payment.domain.DisputeSnapshot;
import com.payments.gateway.payment.domain.DisputeStatus;
import com.payments.gateway.payment.domain.MerchantResponse;
import com.payments.gateway.payment.domain.Review;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.Money;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DisputeRepository {

    private final JdbcClient jdbc;

    public DisputeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Dispute dispute) {
        DisputeSnapshot s = dispute.snapshot();
        Map<String, Object> params = new HashMap<>();
        params.put("id", s.id());
        params.put("status", s.status().name());
        params.put("respondBy", Sql.ts(s.respondBy()));
        params.put("needsReview", s.review().open());
        params.put("reviewReason", s.review().reasons());
        params.put("flaggedAt", Sql.ts(s.review().flaggedAt()));
        params.put("updatedAt", Sql.ts(s.updatedAt()));
        if (dispute.isNew()) {
            params.put("paymentId", s.paymentId());
            params.put("attemptId", s.attemptId());
            params.put("merchantId", s.merchantId());
            params.put("providerCode", s.providerCode());
            params.put("providerDisputeId", s.providerDisputeId());
            params.put("amount", s.amount().amount());
            params.put("currency", s.amount().currency());
            params.put("reason", s.reason());
            params.put("createdAt", Sql.ts(s.createdAt()));
            jdbc.sql("""
                    INSERT INTO disputes (id, payment_id, attempt_id, merchant_id, provider_code, provider_dispute_id,
                                          amount, currency, reason, status, respond_by, needs_review, review_reason,
                                          flagged_at, version, created_at, updated_at)
                    VALUES (:id, :paymentId, :attemptId, :merchantId, :providerCode, :providerDisputeId, :amount,
                            :currency, :reason, :status, :respondBy, :needsReview, :reviewReason, :flaggedAt, 0,
                            :createdAt, :updatedAt)
                    """)
                    .params(params)
                    .update();
        } else {
            MerchantResponse r = s.response();
            params.put("version", s.version());
            params.put("response", r == null ? null : r.type().name());
            params.put("responseStatus", r == null ? null : r.status().name());
            params.put("responseStatement", r == null ? null : r.statement());
            params.put("responseFileIds", r == null || r.fileIds() == null ? null : r.fileIds().toArray(String[]::new));
            params.put("responseRequestedAt", Sql.ts(r == null ? null : r.requestedAt()));
            params.put("responseSentAt", Sql.ts(r == null ? null : r.sentAt()));
            params.put("responseFailure", r == null ? null : r.failure());
            params.put("responseAttempts", r == null ? 0 : r.attempts());
            params.put("responseNextAttemptAt", Sql.ts(r == null ? null : r.nextAttemptAt()));
            params.put("evidenceDueNotifiedAt", Sql.ts(s.evidenceDueNotifiedAt()));
            int updated = jdbc.sql("""
                    UPDATE disputes
                       SET status = :status, respond_by = :respondBy, needs_review = :needsReview,
                           review_reason = :reviewReason, flagged_at = :flaggedAt, response = :response,
                           response_status = :responseStatus, response_statement = :responseStatement,
                           response_file_ids = :responseFileIds, response_requested_at = :responseRequestedAt,
                           response_sent_at = :responseSentAt, response_failure = :responseFailure,
                           response_attempts = :responseAttempts, response_next_attempt_at = :responseNextAttemptAt,
                           evidence_due_notified_at = :evidenceDueNotifiedAt, version = version + 1,
                           updated_at = :updatedAt
                     WHERE id = :id AND version = :version
                    """)
                    .params(params)
                    .update();
            if (updated != 1) {
                throw new OptimisticLockingFailureException("dispute " + s.id() + " was modified concurrently");
            }
        }
        dispute.markPersisted();
    }

    public Optional<Dispute> findById(String id) {
        return jdbc.sql("SELECT * FROM disputes WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<Dispute> findForMerchant(String merchantId, String id) {
        return jdbc.sql("SELECT * FROM disputes WHERE id = :id AND merchant_id = :merchantId")
                .param("id", id)
                .param("merchantId", merchantId)
                .query(this::map)
                .optional();
    }

    public Optional<Dispute> findByProviderDisputeId(String providerCode, String merchantId, String providerDisputeId) {
        return jdbc.sql("""
                SELECT * FROM disputes
                 WHERE provider_code = :provider AND merchant_id = :merchantId AND provider_dispute_id = :disputeId
                """)
                .param("provider", providerCode)
                .param("merchantId", merchantId)
                .param("disputeId", providerDisputeId)
                .query(this::map)
                .optional();
    }

    public List<Dispute> findByPayment(String paymentId) {
        return jdbc.sql("SELECT * FROM disputes WHERE payment_id = :paymentId ORDER BY created_at, id")
                .param("paymentId", paymentId)
                .query(this::map)
                .list();
    }

    /** Disputed amount the PSP still withholds for this attempt (every dispute that is not won). */
    public long sumHoldingFundsForAttempt(String attemptId) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM disputes WHERE attempt_id = :attemptId AND status <> 'WON'")
                .param("attemptId", attemptId)
                .query(Long.class)
                .single();
    }

    /** Claims pending responses that are due, leasing them until {@code leaseUntil} (LLD §10). */
    public List<String> claimDueResponses(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                UPDATE disputes SET response_next_attempt_at = :leaseUntil
                 WHERE id IN (SELECT id FROM disputes
                               WHERE response_status = 'PENDING' AND response_next_attempt_at <= :now
                               ORDER BY response_next_attempt_at
                               LIMIT :limit
                               FOR UPDATE SKIP LOCKED)
                RETURNING id
                """)
                .param("now", Sql.ts(now))
                .param("leaseUntil", Sql.ts(leaseUntil))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /** Open disputes due by {@code cutoff} with no response pending or sent, not yet notified; earliest deadline first. */
    public List<String> findEvidenceDueToNotify(Instant cutoff, int limit) {
        return jdbc.sql("""
                SELECT id FROM disputes
                 WHERE status = 'OPEN' AND respond_by <= :cutoff AND evidence_due_notified_at IS NULL
                   AND (response_status IS NULL OR response_status = 'FAILED')
                 ORDER BY respond_by, id
                 LIMIT :limit
                """)
                .param("cutoff", Sql.ts(cutoff))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /** Open disputes due by {@code cutoff} (or overdue) with no response pending or sent, notified or not. */
    public long countEvidenceDue(Instant cutoff) {
        return jdbc.sql("""
                SELECT count(*) FROM disputes
                 WHERE status = 'OPEN' AND respond_by <= :cutoff
                   AND (response_status IS NULL OR response_status = 'FAILED')
                """)
                .param("cutoff", Sql.ts(cutoff))
                .query(Long.class)
                .single();
    }

    public List<Dispute> findEvidenceDue(Instant cutoff, String merchantId, int limit) {
        return jdbc.sql("""
                SELECT * FROM disputes
                 WHERE status = 'OPEN' AND respond_by <= :cutoff
                   AND (response_status IS NULL OR response_status = 'FAILED')
                   AND (CAST(:merchantId AS text) IS NULL OR merchant_id = :merchantId)
                 ORDER BY respond_by, id
                 LIMIT :limit
                """)
                .param("cutoff", Sql.ts(cutoff))
                .param("merchantId", merchantId)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    private Dispute map(ResultSet rs, int rowNum) throws SQLException {
        return Dispute.rehydrate(new DisputeSnapshot(
                rs.getString("id"),
                rs.getString("payment_id"),
                rs.getString("attempt_id"),
                rs.getString("merchant_id"),
                rs.getString("provider_code"),
                rs.getString("provider_dispute_id"),
                Money.of(rs.getLong("amount"), rs.getString("currency")),
                rs.getString("reason"),
                DisputeStatus.valueOf(rs.getString("status")),
                Sql.instant(rs, "respond_by"),
                new Review(rs.getBoolean("needs_review"), rs.getString("review_reason"), Sql.instant(rs, "flagged_at")),
                rs.getLong("version"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"),
                response(rs),
                Sql.instant(rs, "evidence_due_notified_at")));
    }

    private static MerchantResponse response(ResultSet rs) throws SQLException {
        String type = rs.getString("response");
        if (type == null) {
            return null;
        }
        Array files = rs.getArray("response_file_ids");
        return new MerchantResponse(MerchantResponse.Type.valueOf(type),
                MerchantResponse.Status.valueOf(rs.getString("response_status")), rs.getString("response_statement"),
                files == null ? null : List.of((String[]) files.getArray()), Sql.instant(rs, "response_requested_at"),
                Sql.instant(rs, "response_sent_at"), rs.getString("response_failure"), rs.getInt("response_attempts"),
                Sql.instant(rs, "response_next_attempt_at"));
    }
}
