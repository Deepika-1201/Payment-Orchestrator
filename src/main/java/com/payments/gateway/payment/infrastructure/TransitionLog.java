package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.payment.domain.StatusChange;
import com.payments.gateway.shared.jdbc.Sql;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes to the append-only {@code payment_transitions} table. */
@Repository
public class TransitionLog {

    private final JdbcClient jdbc;

    public TransitionLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String paymentId, String merchantId, List<StatusChange> changes) {
        for (StatusChange change : changes) {
            jdbc.sql("""
                    INSERT INTO payment_transitions (payment_id, merchant_id, entity, entity_id, from_status, to_status,
                                                     source, reason, occurred_at)
                    VALUES (:paymentId, :merchantId, :entity, :entityId, :from, :to, :source, :reason, :occurredAt)
                    """)
                    .param("paymentId", paymentId)
                    .param("merchantId", merchantId)
                    .param("entity", change.entity().name())
                    .param("entityId", change.entityId())
                    .param("from", change.fromStatus())
                    .param("to", change.toStatus())
                    .param("source", change.source().name())
                    .param("reason", change.reason())
                    .param("occurredAt", Sql.ts(change.occurredAt()))
                    .update();
        }
    }
}
