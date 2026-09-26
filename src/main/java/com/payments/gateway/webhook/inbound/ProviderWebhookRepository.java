package com.payments.gateway.webhook.inbound;

import com.payments.gateway.shared.jdbc.Sql;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Inbox of PSP webhooks: stored before processing, deduplicated per provider, receiving account and event id. */
@Repository
public class ProviderWebhookRepository {

    public record PendingEvent(String id, String providerCode, String merchantId, String normalizedEvent, int attempts) {
    }

    private final JdbcClient jdbc;

    public ProviderWebhookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(String id, String providerCode, String merchantAccountId, String merchantId,
                                  String providerEventId, String eventType, String payload, String normalizedEvent,
                                  Instant now) {
        return jdbc.sql("""
                INSERT INTO provider_webhook_events (id, provider_code, merchant_account_id, merchant_id, provider_event_id,
                                                     event_type, payload, normalized_event, status, received_at)
                VALUES (:id, :provider, :accountId, :merchantId, :eventId, :eventType, :payload, CAST(:normalized AS jsonb),
                        'RECEIVED', :now)
                ON CONFLICT ON CONSTRAINT ux_provider_event DO NOTHING
                """)
                .param("id", id)
                .param("provider", providerCode)
                .param("accountId", merchantAccountId)
                .param("merchantId", merchantId)
                .param("eventId", providerEventId)
                .param("eventType", eventType == null ? "unknown" : eventType)
                .param("payload", payload)
                .param("normalized", normalizedEvent)
                .param("now", Sql.ts(now))
                .update() == 1;
    }

    public void markDone(String id, String status, Instant now) {
        jdbc.sql("""
                UPDATE provider_webhook_events
                   SET status = :status, attempts = attempts + 1, processed_at = :now, next_attempt_at = NULL, last_error = NULL
                 WHERE id = :id
                """)
                .param("id", id)
                .param("status", status)
                .param("now", Sql.ts(now))
                .update();
    }

    public void markForRetry(String id, int attempts, Instant nextAttemptAt, String error) {
        jdbc.sql("""
                UPDATE provider_webhook_events
                   SET attempts = :attempts, next_attempt_at = :next, last_error = :error,
                       status = CASE WHEN CAST(:next AS timestamptz) IS NULL THEN 'FAILED' ELSE 'RECEIVED' END
                 WHERE id = :id
                """)
                .param("id", id)
                .param("attempts", attempts)
                .param("next", Sql.ts(nextAttemptAt))
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 1000)))
                .update();
    }

    public List<PendingEvent> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                UPDATE provider_webhook_events SET next_attempt_at = :leaseUntil
                 WHERE id IN (SELECT id FROM provider_webhook_events
                               WHERE status = 'RECEIVED' AND next_attempt_at <= :now
                               ORDER BY next_attempt_at
                               LIMIT :limit
                               FOR UPDATE SKIP LOCKED)
                RETURNING id, provider_code, merchant_id, CAST(normalized_event AS text) AS normalized_event, attempts
                """)
                .param("leaseUntil", Sql.ts(leaseUntil))
                .param("now", Sql.ts(now))
                .param("limit", limit)
                .query((rs, n) -> new PendingEvent(rs.getString("id"), rs.getString("provider_code"),
                        rs.getString("merchant_id"), rs.getString("normalized_event"), rs.getInt("attempts")))
                .list();
    }
}
