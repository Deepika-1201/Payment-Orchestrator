package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.payment.domain.Review;
import com.payments.gateway.payment.domain.TransferCredit;
import com.payments.gateway.payment.domain.TransferCreditSnapshot;
import com.payments.gateway.shared.jdbc.Sql;
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

/** Bank transfer credits (ADR-038). */
@Repository
public class TransferCreditRepository {

    private final JdbcClient jdbc;

    public TransferCreditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(TransferCredit credit) {
        TransferCreditSnapshot s = credit.snapshot();
        Map<String, Object> params = new HashMap<>();
        params.put("id", s.id());
        params.put("appliedAmount", s.appliedAmount());
        params.put("returnedAmount", s.returnedAmount());
        params.put("returnRefundId", s.returnRefundId());
        params.put("needsReview", s.review().open());
        params.put("reviewReason", s.review().reasons());
        params.put("flaggedAt", Sql.ts(s.review().flaggedAt()));
        params.put("updatedAt", Sql.ts(s.updatedAt()));
        if (credit.isNew()) {
            params.put("merchantId", s.merchantId());
            params.put("providerCode", s.providerCode());
            params.put("providerReference", s.providerReference());
            params.put("collectionReference", s.collectionReference());
            params.put("attemptId", s.attemptId());
            params.put("paymentId", s.paymentId());
            params.put("amount", s.amount().amount());
            params.put("currency", s.amount().currency());
            params.put("mode", s.mode());
            params.put("utr", s.utr());
            params.put("receivedAt", Sql.ts(s.receivedAt()));
            params.put("createdAt", Sql.ts(s.createdAt()));
            jdbc.sql("""
                    INSERT INTO transfer_credits (id, merchant_id, provider_code, provider_reference, collection_reference,
                                                  attempt_id, payment_id, amount, currency, mode, utr, received_at,
                                                  applied_amount, returned_amount, return_refund_id, needs_review,
                                                  review_reason, flagged_at, version, created_at, updated_at)
                    VALUES (:id, :merchantId, :providerCode, :providerReference, :collectionReference, :attemptId,
                            :paymentId, :amount, :currency, :mode, :utr, :receivedAt, :appliedAmount, :returnedAmount,
                            :returnRefundId, :needsReview, :reviewReason, :flaggedAt, 0, :createdAt, :updatedAt)
                    """)
                    .params(params)
                    .update();
        } else {
            params.put("version", s.version());
            int updated = jdbc.sql("""
                    UPDATE transfer_credits
                       SET applied_amount = :appliedAmount, returned_amount = :returnedAmount,
                           return_refund_id = :returnRefundId, needs_review = :needsReview,
                           review_reason = :reviewReason, flagged_at = :flaggedAt,
                           version = version + 1, updated_at = :updatedAt
                     WHERE id = :id AND version = :version
                    """)
                    .params(params)
                    .update();
            if (updated != 1) {
                throw new OptimisticLockingFailureException("credit " + s.id() + " was modified concurrently");
            }
        }
        credit.markPersisted();
    }

    public Optional<TransferCredit> findById(String id) {
        return jdbc.sql("SELECT * FROM transfer_credits WHERE id = :id").param("id", id)
                .query(TransferCreditRepository::map).optional();
    }

    public Optional<TransferCredit> findByProviderReference(String providerCode, String providerReference) {
        return jdbc.sql("SELECT * FROM transfer_credits WHERE provider_code = :provider AND provider_reference = :reference")
                .param("provider", providerCode)
                .param("reference", providerReference)
                .query(TransferCreditRepository::map)
                .optional();
    }

    /** Oldest first. */
    public List<TransferCredit> findByAttempt(String attemptId) {
        return jdbc.sql("SELECT * FROM transfer_credits WHERE attempt_id = :attemptId ORDER BY received_at, id")
                .param("attemptId", attemptId)
                .query(TransferCreditRepository::map)
                .list();
    }

    public List<TransferCredit> findByPayment(String paymentId) {
        return jdbc.sql("SELECT * FROM transfer_credits WHERE payment_id = :paymentId ORDER BY received_at, id")
                .param("paymentId", paymentId)
                .query(TransferCreditRepository::map)
                .list();
    }

    public long sumApplied(String attemptId) {
        return jdbc.sql("SELECT COALESCE(SUM(applied_amount), 0) FROM transfer_credits WHERE attempt_id = :attemptId")
                .param("attemptId", attemptId)
                .query(Long.class)
                .single();
    }

    /** Credits one merchant PSP account received in [from, to), for reconciliation (LLD §21.6). */
    public List<TransferCredit> findReceivedBetween(String merchantId, String providerCode, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT * FROM transfer_credits
                 WHERE merchant_id = :merchantId AND provider_code = :provider
                   AND received_at >= :from AND received_at < :to
                 ORDER BY received_at, id
                """)
                .param("merchantId", merchantId)
                .param("provider", providerCode)
                .param("from", Sql.ts(from))
                .param("to", Sql.ts(to))
                .query(TransferCreditRepository::map)
                .list();
    }

    private static TransferCredit map(ResultSet rs, int rowNum) throws SQLException {
        return TransferCredit.rehydrate(new TransferCreditSnapshot(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("provider_code"),
                rs.getString("provider_reference"),
                rs.getString("collection_reference"),
                rs.getString("attempt_id"),
                rs.getString("payment_id"),
                Money.of(rs.getLong("amount"), rs.getString("currency")),
                rs.getString("mode"),
                rs.getString("utr"),
                Sql.instant(rs, "received_at"),
                rs.getLong("applied_amount"),
                rs.getLong("returned_amount"),
                rs.getString("return_refund_id"),
                new Review(rs.getBoolean("needs_review"), rs.getString("review_reason"), Sql.instant(rs, "flagged_at")),
                rs.getLong("version"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at")));
    }
}
