package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.payment.domain.Dispute;
import com.payments.gateway.payment.domain.DisputeSnapshot;
import com.payments.gateway.payment.domain.DisputeStatus;
import com.payments.gateway.payment.domain.Review;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
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
            params.put("version", s.version());
            int updated = jdbc.sql("""
                    UPDATE disputes
                       SET status = :status, respond_by = :respondBy, needs_review = :needsReview,
                           review_reason = :reviewReason, flagged_at = :flaggedAt, version = version + 1,
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
                Sql.instant(rs, "updated_at")));
    }
}
