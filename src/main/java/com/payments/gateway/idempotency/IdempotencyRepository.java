package com.payments.gateway.idempotency;

import com.payments.gateway.shared.jdbc.Sql;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record StoredRecord(byte[] requestHash, boolean completed, Integer responseStatus, String responseBody,
                               Instant lockedUntil) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Autocommitted so concurrent duplicates observe the in-progress record immediately. */
    public boolean tryInsert(String merchantId, String key, byte[] requestHash, Instant now, Instant lockedUntil,
                             Instant expiresAt) {
        return jdbc.sql("""
                INSERT INTO idempotency_records (merchant_id, idempotency_key, request_hash, status, locked_until, created_at, expires_at)
                VALUES (:merchantId, :key, :hash, 'IN_PROGRESS', :lockedUntil, :now, :expiresAt)
                ON CONFLICT DO NOTHING
                """)
                .param("merchantId", merchantId)
                .param("key", key)
                .param("hash", requestHash)
                .param("lockedUntil", Sql.ts(lockedUntil))
                .param("now", Sql.ts(now))
                .param("expiresAt", Sql.ts(expiresAt))
                .update() == 1;
    }

    public Optional<StoredRecord> find(String merchantId, String key) {
        return jdbc.sql("""
                SELECT request_hash, status, response_status, response_body, locked_until
                  FROM idempotency_records WHERE merchant_id = :merchantId AND idempotency_key = :key
                """)
                .param("merchantId", merchantId)
                .param("key", key)
                .query((rs, n) -> new StoredRecord(rs.getBytes("request_hash"), "COMPLETED".equals(rs.getString("status")),
                        (Integer) rs.getObject("response_status"), rs.getString("response_body"),
                        Sql.instant(rs, "locked_until")))
                .optional();
    }

    public boolean takeOver(String merchantId, String key, Instant now, Instant lockedUntil) {
        return jdbc.sql("""
                UPDATE idempotency_records SET locked_until = :lockedUntil
                 WHERE merchant_id = :merchantId AND idempotency_key = :key
                   AND status = 'IN_PROGRESS' AND locked_until < :now
                """)
                .param("merchantId", merchantId)
                .param("key", key)
                .param("now", Sql.ts(now))
                .param("lockedUntil", Sql.ts(lockedUntil))
                .update() == 1;
    }

    public void complete(String merchantId, String key, int status, String body) {
        jdbc.sql("""
                UPDATE idempotency_records
                   SET status = 'COMPLETED', response_status = :status, response_body = :body, locked_until = NULL
                 WHERE merchant_id = :merchantId AND idempotency_key = :key
                """)
                .param("merchantId", merchantId)
                .param("key", key)
                .param("status", status)
                .param("body", body)
                .update();
    }

    public void delete(String merchantId, String key) {
        jdbc.sql("DELETE FROM idempotency_records WHERE merchant_id = :merchantId AND idempotency_key = :key")
                .param("merchantId", merchantId)
                .param("key", key)
                .update();
    }

    public int deleteExpired(Instant now, int limit) {
        return jdbc.sql("""
                DELETE FROM idempotency_records WHERE ctid IN (
                    SELECT ctid FROM idempotency_records WHERE expires_at < :now LIMIT :limit)
                """)
                .param("now", Sql.ts(now))
                .param("limit", limit)
                .update();
    }
}
