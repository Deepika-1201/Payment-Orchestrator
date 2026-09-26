package com.payments.gateway.payment.domain;

import java.util.List;

/** The risk engine's decision for an attempt, kept for audit and review. */
public record RiskAssessment(String outcome, List<String> reasons) {

    public RiskAssessment {
        reasons = List.copyOf(reasons);
    }
}
