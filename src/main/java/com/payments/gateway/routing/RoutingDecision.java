package com.payments.gateway.routing;

import java.util.List;

/** Ordered provider candidates; the orchestrator tries them in order only on definite unavailability. */
public record RoutingDecision(List<String> providers, String ruleId, RoutingStrategy strategy, Reason reason) {

    public enum Reason {
        ROUTED,
        NO_CAPABLE_PROVIDER,
        ALL_UNAVAILABLE
    }

    public RoutingDecision {
        providers = List.copyOf(providers);
    }

    public static RoutingDecision none(Reason reason) {
        return new RoutingDecision(List.of(), null, null, reason);
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }
}
