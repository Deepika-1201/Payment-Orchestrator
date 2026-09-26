package com.payments.gateway.risk;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final List<RiskRule> rules;

    public RiskEngine(ObjectProvider<RiskRule> rules) {
        this.rules = rules.orderedStream().toList();
    }

    public RiskDecision evaluate(RiskContext context) {
        RiskDecision.Outcome outcome = RiskDecision.Outcome.ALLOW;
        List<String> reasons = new ArrayList<>();
        for (RiskRule rule : rules) {
            RiskDecision decision = rule.evaluate(context);
            if (decision.outcome() != RiskDecision.Outcome.ALLOW) {
                reasons.addAll(decision.reasons());
                if (decision.outcome().compareTo(outcome) > 0) {
                    outcome = decision.outcome();
                }
            }
        }
        if (outcome != RiskDecision.Outcome.ALLOW) {
            log.info("Risk decision {} for payment {}: {}", outcome, context.paymentId(), reasons);
        }
        return new RiskDecision(outcome, reasons);
    }
}
