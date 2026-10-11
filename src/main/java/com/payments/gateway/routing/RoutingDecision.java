package com.payments.gateway.routing;

import java.math.BigDecimal;
import java.util.List;

/** Ordered provider candidates; the orchestrator tries them in order only on definite unavailability. */
public record RoutingDecision(List<String> providers, String ruleId, RoutingStrategy strategy, Reason reason,
                              Evaluation live, Evaluation shadow) {

    public record Estimate(String provider, double predictedSuccess, int samples, boolean bankSpecific,
                           String feeScheduleId, Long feeAmount, String currency, BigDecimal costPerSuccess,
                           Double sampledSuccess) {
    }

    public record Evaluation(RoutingStrategy strategy, List<String> providers, String reason, boolean exploration,
                             List<Estimate> estimates) {
        public Evaluation {
            providers = List.copyOf(providers);
            estimates = List.copyOf(estimates);
        }
    }

    public enum Reason {
        ROUTED,
        NO_CAPABLE_PROVIDER,
        ALL_UNAVAILABLE
    }

    public RoutingDecision {
        providers = List.copyOf(providers);
    }

    public RoutingDecision(List<String> providers, String ruleId, RoutingStrategy strategy, Reason reason) {
        this(providers, ruleId, strategy, reason,
                new Evaluation(strategy, providers, reason.name().toLowerCase(java.util.Locale.ROOT), false, List.of()), null);
    }

    public static RoutingDecision none(Reason reason) {
        return new RoutingDecision(List.of(), null, null, reason);
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }
}
