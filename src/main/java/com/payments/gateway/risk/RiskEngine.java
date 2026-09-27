package com.payments.gateway.risk;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final List<RiskRule> rules;
    private final MeterRegistry meters;

    public RiskEngine(ObjectProvider<RiskRule> rules, MeterRegistry meters) {
        this.rules = rules.orderedStream().toList();
        this.meters = meters;
        this.rules.forEach(rule -> meters.counter("pg.risk.rule_errors", "rule", rule.getClass().getSimpleName()));
    }

    public RiskDecision evaluate(RiskContext context) {
        RiskDecision.Outcome outcome = RiskDecision.Outcome.ALLOW;
        List<String> reasons = new ArrayList<>();
        for (RiskRule rule : rules) {
            RiskDecision decision = safely(rule, context);
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
        meters.counter("pg.risk.decisions", "outcome", outcome.name().toLowerCase(Locale.ROOT)).increment();
        return new RiskDecision(outcome, reasons);
    }

    /** A broken rule must not fail the payment or silently allow it: it sends the payment to review instead. */
    private RiskDecision safely(RiskRule rule, RiskContext context) {
        try {
            return rule.evaluate(context);
        } catch (RuntimeException e) {
            log.error("Risk rule {} failed for payment {}", rule.getClass().getSimpleName(), context.paymentId(), e);
            meters.counter("pg.risk.rule_errors", "rule", rule.getClass().getSimpleName()).increment();
            return RiskDecision.of(RiskDecision.Outcome.REVIEW, "risk_rule_error");
        }
    }
}
