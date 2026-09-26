package com.payments.gateway.routing;

import java.time.Instant;
import java.util.List;

public record RoutingRule(String id, String merchantId, String name, int priority, boolean enabled,
                          List<RuleCondition> conditions, RoutingStrategy strategy, List<Target> targets,
                          boolean allowFallback, long version, Instant createdAt, Instant updatedAt) {

    public record Target(String provider, int weight) {
    }

    public RoutingRule {
        conditions = List.copyOf(conditions);
        targets = List.copyOf(targets);
    }

    public boolean matches(RoutingContext context) {
        return enabled && conditions.stream().allMatch(condition -> condition.matches(context));
    }
}
