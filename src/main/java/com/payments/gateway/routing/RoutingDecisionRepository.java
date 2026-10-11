package com.payments.gateway.routing;

import com.payments.gateway.routing.RoutingDecision.Evaluation;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.PaymentMethod;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoutingDecisionRepository {

    public record RecordedDecision(String attemptId, String merchantId, String ruleId, String method, String bankCode,
                                    String chosenProvider, Evaluation live, Evaluation shadow, Instant evaluatedAt) {
    }

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public RoutingDecisionRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String merchantId, String attemptId, PaymentMethod method, RoutingDecision decision, Instant at) {
        if (decision.shadow() == null && decision.strategy() != RoutingStrategy.COST && decision.strategy() != RoutingStrategy.ADAPTIVE) {
            return;
        }
        jdbc.sql("""
                INSERT INTO routing_decisions (attempt_id, merchant_id, rule_id, method, bank_code, live_strategy,
                    chosen_provider, live_evaluation, shadow_strategy, shadow_provider, shadow_evaluation, evaluated_at)
                VALUES (:attempt, :merchant, :rule, :method, :bank, :strategy, :chosen, CAST(:live AS jsonb),
                        :shadowStrategy, :shadowProvider, CAST(:shadow AS jsonb), :at)
                ON CONFLICT (attempt_id) DO NOTHING
                """)
                .param("attempt", attemptId).param("merchant", merchantId).param("rule", decision.ruleId())
                .param("method", method.type().name()).param("bank", method.bankCode()).param("strategy", decision.strategy().name())
                .param("chosen", decision.providers().getFirst()).param("live", json.write(decision.live()))
                .param("shadowStrategy", decision.shadow() == null ? null : decision.shadow().strategy().name())
                .param("shadowProvider", decision.shadow() == null ? null : decision.shadow().providers().getFirst())
                .param("shadow", decision.shadow() == null ? null : json.write(decision.shadow())).param("at", Sql.ts(at)).update();
    }

    public List<RecordedDecision> recent(String merchantId, int limit) {
        return jdbc.sql("""
                SELECT * FROM routing_decisions WHERE merchant_id = :merchant
                 ORDER BY evaluated_at DESC, attempt_id DESC LIMIT :limit
                """).param("merchant", merchantId).param("limit", limit)
                .query((result, row) -> new RecordedDecision(result.getString("attempt_id"), result.getString("merchant_id"),
                        result.getString("rule_id"), result.getString("method"), result.getString("bank_code"),
                        result.getString("chosen_provider"), json.read(result.getString("live_evaluation"), Evaluation.class),
                        result.getString("shadow_evaluation") == null ? null : json.read(result.getString("shadow_evaluation"), Evaluation.class),
                        Sql.instant(result, "evaluated_at"))).list();
    }

    public int purgeBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM routing_decisions WHERE attempt_id IN (
                    SELECT attempt_id FROM routing_decisions WHERE evaluated_at < :cutoff
                     ORDER BY evaluated_at, attempt_id LIMIT :limit FOR UPDATE SKIP LOCKED)
                """).param("cutoff", Sql.ts(cutoff)).param("limit", limit).update();
    }
}