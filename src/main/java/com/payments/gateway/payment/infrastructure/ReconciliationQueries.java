package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read model for reconciliation, derived from the append-only transition log. */
@Repository
public class ReconciliationQueries {

    public record SucceededItem(String entity, String entityId, String paymentId, Money amount, Instant succeededAt) {
    }

    private final JdbcClient jdbc;

    public ReconciliationQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Attempts captured and refunds succeeded for one merchant PSP account within [from, to); bank transfer attempts are
     *  left out, their credits being the PSP's payments (LLD §21.6). */
    public List<SucceededItem> succeededBetween(String merchantId, String providerCode, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT t.entity, t.entity_id, t.payment_id, t.occurred_at,
                       COALESCE(a.capture_amount, r.amount) AS amount, COALESCE(a.currency, r.currency) AS currency
                  FROM payment_transitions t
                  LEFT JOIN payment_attempts a ON t.entity = 'ATTEMPT' AND a.id = t.entity_id
                  LEFT JOIN refunds r ON t.entity = 'REFUND' AND r.id = t.entity_id
                 WHERE t.merchant_id = :merchantId
                   AND t.to_status = 'SUCCEEDED' AND t.entity IN ('ATTEMPT', 'REFUND')
                   AND t.occurred_at >= :from AND t.occurred_at < :to
                   AND COALESCE(a.provider_code, r.provider_code) = :provider
                   AND (a.method_type IS NULL OR a.method_type <> 'BANK_TRANSFER')
                 ORDER BY t.occurred_at, t.id
                """)
                .param("merchantId", merchantId)
                .param("provider", providerCode)
                .param("from", Sql.ts(from))
                .param("to", Sql.ts(to))
                .query((rs, n) -> new SucceededItem(rs.getString("entity"), rs.getString("entity_id"), rs.getString("payment_id"),
                        Money.of(rs.getLong("amount"), rs.getString("currency")), Sql.instant(rs, "occurred_at")))
                .list();
    }
}
