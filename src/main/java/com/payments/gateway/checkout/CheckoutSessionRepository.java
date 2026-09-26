package com.payments.gateway.checkout;

import com.payments.gateway.shared.jdbc.Sql;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CheckoutSessionRepository {

    private final JdbcClient jdbc;

    public CheckoutSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CheckoutSession session, byte[] tokenHash) {
        jdbc.sql("""
                INSERT INTO checkout_sessions (id, merchant_id, payment_id, token_hash, return_url, expires_at, created_at)
                VALUES (:id, :merchantId, :paymentId, :tokenHash, :returnUrl, :expiresAt, :createdAt)
                """)
                .param("id", session.id())
                .param("merchantId", session.merchantId())
                .param("paymentId", session.paymentId())
                .param("tokenHash", tokenHash)
                .param("returnUrl", session.returnUrl())
                .param("expiresAt", Sql.ts(session.expiresAt()))
                .param("createdAt", Sql.ts(session.createdAt()))
                .update();
    }

    public Optional<CheckoutSession> findByTokenHash(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM checkout_sessions WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query((rs, n) -> new CheckoutSession(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getString("payment_id"),
                        rs.getString("return_url"),
                        Sql.instant(rs, "expires_at"),
                        Sql.instant(rs, "created_at")))
                .optional();
    }

    public int deleteExpiredBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM checkout_sessions
                 WHERE id IN (SELECT id FROM checkout_sessions WHERE expires_at < :cutoff LIMIT :limit)
                """)
                .param("cutoff", Sql.ts(cutoff))
                .param("limit", limit)
                .update();
    }
}
