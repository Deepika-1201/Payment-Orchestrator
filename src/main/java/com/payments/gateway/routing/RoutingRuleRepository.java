package com.payments.gateway.routing;

import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.json.JsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
public class RoutingRuleRepository {

    private static final TypeReference<List<RuleCondition>> CONDITIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<RoutingRule.Target>> TARGETS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public RoutingRuleRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<RoutingRule> findAll() {
        return jdbc.sql("SELECT * FROM routing_rules ORDER BY merchant_id NULLS LAST, priority, id")
                .query(this::map)
                .list();
    }

    public Optional<RoutingRule> findById(String id) {
        return jdbc.sql("SELECT * FROM routing_rules WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public void insert(RoutingRule rule) {
        jdbc.sql("""
                INSERT INTO routing_rules (id, merchant_id, name, priority, enabled, conditions, strategy, targets,
                                           allow_fallback, version, created_at, updated_at)
                VALUES (:id, :merchantId, :name, :priority, :enabled, CAST(:conditions AS jsonb), :strategy,
                        CAST(:targets AS jsonb), :allowFallback, 0, :createdAt, :updatedAt)
                """)
                .params(params(rule))
                .update();
    }

    public boolean update(RoutingRule rule) {
        return jdbc.sql("""
                UPDATE routing_rules
                   SET merchant_id = :merchantId, name = :name, priority = :priority, enabled = :enabled,
                       conditions = CAST(:conditions AS jsonb), strategy = :strategy, targets = CAST(:targets AS jsonb),
                       allow_fallback = :allowFallback, version = version + 1, updated_at = :updatedAt
                 WHERE id = :id AND version = :version
                """)
                .params(params(rule))
                .param("version", rule.version())
                .update() == 1;
    }

    private java.util.Map<String, Object> params(RoutingRule rule) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("id", rule.id());
        params.put("merchantId", rule.merchantId());
        params.put("name", rule.name());
        params.put("priority", rule.priority());
        params.put("enabled", rule.enabled());
        params.put("conditions", json.write(rule.conditions()));
        params.put("strategy", rule.strategy().name());
        params.put("targets", json.write(rule.targets()));
        params.put("allowFallback", rule.allowFallback());
        params.put("createdAt", Sql.ts(rule.createdAt()));
        params.put("updatedAt", Sql.ts(rule.updatedAt()));
        return params;
    }

    private RoutingRule map(ResultSet rs, int rowNum) throws SQLException {
        return new RoutingRule(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("name"),
                rs.getInt("priority"),
                rs.getBoolean("enabled"),
                json.read(rs.getString("conditions"), CONDITIONS),
                RoutingStrategy.valueOf(rs.getString("strategy")),
                json.read(rs.getString("targets"), TARGETS),
                rs.getBoolean("allow_fallback"),
                rs.getLong("version"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"));
    }
}
