package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.payment.domain.Failure;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.payment.domain.RefundInitiator;
import com.payments.gateway.payment.domain.RefundSnapshot;
import com.payments.gateway.payment.domain.RefundStatus;
import com.payments.gateway.payment.domain.Review;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.Money;
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
public class RefundRepository {

    private final JdbcClient jdbc;

    public RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Refund refund) {
        RefundSnapshot s = refund.snapshot();
        Map<String, Object> params = new HashMap<>();
        params.put("id", s.id());
        params.put("status", s.status().name());
        params.put("providerReference", s.providerReference());
        params.put("failureCode", s.failure() == null ? null : s.failure().code());
        params.put("failureMessage", s.failure() == null ? null : s.failure().message());
        params.put("nextStatusCheckAt", Sql.ts(s.nextStatusCheckAt()));
        params.put("statusCheckCount", s.statusCheckCount());
        params.put("needsReview", s.review().open());
        params.put("reviewReason", s.review().reasons());
        params.put("flaggedAt", Sql.ts(s.review().flaggedAt()));
        params.put("updatedAt", Sql.ts(s.updatedAt()));
        if (refund.isNew()) {
            params.put("paymentId", s.paymentId());
            params.put("attemptId", s.attemptId());
            params.put("merchantId", s.merchantId());
            params.put("providerCode", s.providerCode());
            params.put("amount", s.amount().amount());
            params.put("currency", s.amount().currency());
            params.put("reason", s.reason());
            params.put("merchantRefundId", s.merchantRefundId());
            params.put("initiatedBy", s.initiatedBy().name());
            params.put("createdAt", Sql.ts(s.createdAt()));
            jdbc.sql("""
                    INSERT INTO refunds (id, payment_id, attempt_id, merchant_id, provider_code, amount, currency, status,
                                         reason, merchant_refund_id, initiated_by, provider_reference, failure_code,
                                         failure_message, next_status_check_at, status_check_count, needs_review,
                                         review_reason, flagged_at, version, created_at, updated_at)
                    VALUES (:id, :paymentId, :attemptId, :merchantId, :providerCode, :amount, :currency, :status, :reason,
                            :merchantRefundId, :initiatedBy, :providerReference, :failureCode, :failureMessage,
                            :nextStatusCheckAt, :statusCheckCount, :needsReview, :reviewReason, :flaggedAt, 0,
                            :createdAt, :updatedAt)
                    """)
                    .params(params)
                    .update();
        } else {
            params.put("version", s.version());
            int updated = jdbc.sql("""
                    UPDATE refunds
                       SET status = :status, provider_reference = :providerReference, failure_code = :failureCode,
                           failure_message = :failureMessage, next_status_check_at = :nextStatusCheckAt,
                           status_check_count = :statusCheckCount, needs_review = :needsReview,
                           review_reason = :reviewReason, flagged_at = :flaggedAt,
                           version = version + 1, updated_at = :updatedAt
                     WHERE id = :id AND version = :version
                    """)
                    .params(params)
                    .update();
            if (updated != 1) {
                throw new OptimisticLockingFailureException("refund " + s.id() + " was modified concurrently");
            }
        }
        refund.markPersisted();
    }

    public Optional<Refund> findById(String id) {
        return jdbc.sql("SELECT * FROM refunds WHERE id = :id").param("id", id).query(RefundRepository::map).optional();
    }

    public Optional<Refund> findForMerchant(String merchantId, String id) {
        return jdbc.sql("SELECT * FROM refunds WHERE id = :id AND merchant_id = :merchantId")
                .param("id", id)
                .param("merchantId", merchantId)
                .query(RefundRepository::map)
                .optional();
    }

    public List<Refund> findByPayment(String paymentId) {
        return jdbc.sql("SELECT * FROM refunds WHERE payment_id = :paymentId ORDER BY created_at, id")
                .param("paymentId", paymentId)
                .query(RefundRepository::map)
                .list();
    }

    /** Sum of refunds that may still move money (everything except FAILED). */
    public long sumActiveForAttempt(String attemptId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM refunds WHERE attempt_id = :attemptId AND status <> 'FAILED'")
                .param("attemptId", attemptId)
                .query(Long.class)
                .single();
    }

    public Optional<Refund> findByMerchantRefundId(String merchantId, String merchantRefundId) {
        return jdbc.sql("SELECT * FROM refunds WHERE merchant_id = :merchantId AND merchant_refund_id = :merchantRefundId")
                .param("merchantId", merchantId)
                .param("merchantRefundId", merchantRefundId)
                .query(RefundRepository::map)
                .optional();
    }

    public Optional<Refund> findByProviderReference(String providerCode, String providerReference) {
        return jdbc.sql("SELECT * FROM refunds WHERE provider_code = :provider AND provider_reference = :reference")
                .param("provider", providerCode)
                .param("reference", providerReference)
                .query(RefundRepository::map)
                .optional();
    }

    public List<String> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                UPDATE refunds SET next_status_check_at = :leaseUntil
                 WHERE id IN (SELECT id FROM refunds
                               WHERE next_status_check_at <= :now
                               ORDER BY next_status_check_at
                               LIMIT :limit
                               FOR UPDATE SKIP LOCKED)
                RETURNING id
                """)
                .param("leaseUntil", Sql.ts(leaseUntil))
                .param("now", Sql.ts(now))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    private static Refund map(ResultSet rs, int rowNum) throws SQLException {
        String failureCode = rs.getString("failure_code");
        Failure failure = failureCode == null ? null : new Failure(failureCode, FailureCategory.PROVIDER, rs.getString("failure_message"));
        return Refund.rehydrate(new RefundSnapshot(
                rs.getString("id"),
                rs.getString("payment_id"),
                rs.getString("attempt_id"),
                rs.getString("merchant_id"),
                rs.getString("provider_code"),
                Money.of(rs.getLong("amount"), rs.getString("currency")),
                RefundStatus.valueOf(rs.getString("status")),
                rs.getString("reason"),
                rs.getString("merchant_refund_id"),
                RefundInitiator.valueOf(rs.getString("initiated_by")),
                rs.getString("provider_reference"),
                failure,
                Sql.instant(rs, "next_status_check_at"),
                rs.getInt("status_check_count"),
                new Review(rs.getBoolean("needs_review"), rs.getString("review_reason"), Sql.instant(rs, "flagged_at")),
                rs.getLong("version"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at")));
    }
}
