package com.payments.gateway.webhook.outbound;

import com.payments.gateway.shared.jdbc.Sql;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Outbox tables for merchant notifications: {@code merchant_events} and {@code webhook_deliveries}. */
@Repository
public class MerchantWebhookRepository {

    public record DueDelivery(String id, String eventId, String eventType, String merchantId, String url,
                              int attemptCount, String payload) {
    }

    public record DeliveryView(String id, String eventId, String eventType, String status, int attemptCount,
                               Integer lastResponseStatus, String lastError, Instant nextAttemptAt) {
    }

    private final JdbcClient jdbc;

    public MerchantWebhookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertEvent(String id, String merchantId, String type, String resourceId, String payload, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO merchant_events (id, merchant_id, type, resource_id, payload, created_at)
                VALUES (:id, :merchantId, :type, :resourceId, :payload, :createdAt)
                """)
                .param("id", id)
                .param("merchantId", merchantId)
                .param("type", type)
                .param("resourceId", resourceId)
                .param("payload", payload)
                .param("createdAt", Sql.ts(createdAt))
                .update();
    }

    public void insertDelivery(String id, String eventId, String merchantId, String url, Instant now) {
        jdbc.sql("""
                INSERT INTO webhook_deliveries (id, event_id, merchant_id, url, status, next_attempt_at, created_at, updated_at)
                VALUES (:id, :eventId, :merchantId, :url, 'PENDING', :now, :now, :now)
                """)
                .param("id", id)
                .param("eventId", eventId)
                .param("merchantId", merchantId)
                .param("url", url)
                .param("now", Sql.ts(now))
                .update();
    }

    /** Stored payload text is the exact byte sequence that is signed and delivered. */
    public List<DueDelivery> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                WITH claimed AS (
                    UPDATE webhook_deliveries SET next_attempt_at = :leaseUntil
                     WHERE id IN (SELECT id FROM webhook_deliveries
                                   WHERE status = 'PENDING' AND next_attempt_at <= :now
                                   ORDER BY next_attempt_at
                                   LIMIT :limit
                                   FOR UPDATE SKIP LOCKED)
                    RETURNING id, event_id, merchant_id, url, attempt_count)
                SELECT c.id, c.event_id, e.type, c.merchant_id, c.url, c.attempt_count, e.payload
                  FROM claimed c JOIN merchant_events e ON e.id = c.event_id
                """)
                .param("leaseUntil", Sql.ts(leaseUntil))
                .param("now", Sql.ts(now))
                .param("limit", limit)
                .query((rs, n) -> new DueDelivery(rs.getString("id"), rs.getString("event_id"), rs.getString("type"),
                        rs.getString("merchant_id"), rs.getString("url"), rs.getInt("attempt_count"), rs.getString("payload")))
                .list();
    }

    public void markSucceeded(String id, int attemptCount, int responseStatus, Instant now) {
        jdbc.sql("""
                UPDATE webhook_deliveries
                   SET status = 'SUCCEEDED', attempt_count = :attempts, last_response_status = :status,
                       last_error = NULL, next_attempt_at = NULL, updated_at = :now
                 WHERE id = :id
                """)
                .param("id", id)
                .param("attempts", attemptCount)
                .param("status", responseStatus)
                .param("now", Sql.ts(now))
                .update();
    }

    public void markFailed(String id, int attemptCount, Instant nextAttemptAt, Integer responseStatus, String error, Instant now) {
        jdbc.sql("""
                UPDATE webhook_deliveries
                   SET status = :status, attempt_count = :attempts, next_attempt_at = :next,
                       last_response_status = :responseStatus, last_error = :error, updated_at = :now
                 WHERE id = :id
                """)
                .param("id", id)
                .param("status", nextAttemptAt == null ? "DEAD" : "PENDING")
                .param("attempts", attemptCount)
                .param("next", Sql.ts(nextAttemptAt))
                .param("responseStatus", responseStatus)
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 1000)))
                .param("now", Sql.ts(now))
                .update();
    }

    public boolean requeue(String id, Instant now) {
        return jdbc.sql("""
                UPDATE webhook_deliveries
                   SET status = 'PENDING', attempt_count = 0, next_attempt_at = :now, updated_at = :now
                 WHERE id = :id AND status IN ('DEAD', 'SUCCEEDED')
                """)
                .param("id", id)
                .param("now", Sql.ts(now))
                .update() == 1;
    }

    /** One batch of finished deliveries created before {@code cutoff}; pending ones are kept. */
    public int deleteFinishedDeliveriesBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM webhook_deliveries WHERE id IN (
                    SELECT id FROM webhook_deliveries WHERE created_at < :cutoff AND status <> 'PENDING' LIMIT :limit)
                """)
                .param("cutoff", Sql.ts(cutoff))
                .param("limit", limit)
                .update();
    }

    /** One batch of events created before {@code cutoff} that no longer have any delivery. */
    public int deleteUndeliveredEventsBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM merchant_events WHERE id IN (
                    SELECT e.id FROM merchant_events e
                     WHERE e.created_at < :cutoff
                       AND NOT EXISTS (SELECT 1 FROM webhook_deliveries d WHERE d.event_id = e.id)
                     LIMIT :limit)
                """)
                .param("cutoff", Sql.ts(cutoff))
                .param("limit", limit)
                .update();
    }

    public List<DeliveryView> findByResource(String resourceId) {
        return jdbc.sql("""
                SELECT d.id, d.event_id, e.type, d.status, d.attempt_count, d.last_response_status, d.last_error, d.next_attempt_at
                  FROM webhook_deliveries d JOIN merchant_events e ON e.id = d.event_id
                 WHERE e.resource_id = :resourceId
                 ORDER BY d.created_at, d.id
                """)
                .param("resourceId", resourceId)
                .query((rs, n) -> new DeliveryView(rs.getString("id"), rs.getString("event_id"), rs.getString("type"),
                        rs.getString("status"), rs.getInt("attempt_count"), (Integer) rs.getObject("last_response_status"),
                        rs.getString("last_error"), Sql.instant(rs, "next_attempt_at")))
                .list();
    }

    public Optional<String> findStatus(String id) {
        return jdbc.sql("SELECT status FROM webhook_deliveries WHERE id = :id").param("id", id).query(String.class).optional();
    }
}
