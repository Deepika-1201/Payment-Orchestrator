package com.payments.gateway.shared.audit;

import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.web.Mdc;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Append-only audit trail for administrative actions. */
@Component
public class AuditLogger {

    private final JdbcClient jdbc;
    private final JsonCodec json;
    private final Clock clock;

    public AuditLogger(JdbcClient jdbc, JsonCodec json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public void record(String actorType, String actorId, String action, String resourceType, String resourceId,
                       Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO audit_log (actor_type, actor_id, action, resource_type, resource_id, details, request_id, occurred_at)
                VALUES (:actorType, :actorId, :action, :resourceType, :resourceId, CAST(:details AS jsonb), :requestId, :occurredAt)
                """)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("details", json.write(details))
                .param("requestId", Mdc.requestId())
                .param("occurredAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }
}
