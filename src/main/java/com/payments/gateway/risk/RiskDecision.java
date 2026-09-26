package com.payments.gateway.risk;

import java.util.List;

public record RiskDecision(Outcome outcome, List<String> reasons) {

    /** Ordered by severity; the most severe outcome across rules wins. */
    public enum Outcome {
        ALLOW,
        REVIEW,
        CHALLENGE,
        BLOCK
    }

    public RiskDecision {
        reasons = List.copyOf(reasons);
    }

    public static RiskDecision allow() {
        return new RiskDecision(Outcome.ALLOW, List.of());
    }

    public static RiskDecision of(Outcome outcome, String reason) {
        return new RiskDecision(outcome, List.of(reason));
    }
}
