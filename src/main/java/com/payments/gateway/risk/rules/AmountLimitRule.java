package com.payments.gateway.risk.rules;

import com.payments.gateway.risk.RiskContext;
import com.payments.gateway.risk.RiskDecision;
import com.payments.gateway.risk.RiskProperties;
import com.payments.gateway.risk.RiskRule;
import org.springframework.stereotype.Component;

@Component
public class AmountLimitRule implements RiskRule {

    private final RiskProperties properties;

    public AmountLimitRule(RiskProperties properties) {
        this.properties = properties;
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        long amount = context.amount().amount();
        if (properties.blockThreshold() > 0 && amount > properties.blockThreshold()) {
            return RiskDecision.of(RiskDecision.Outcome.BLOCK, "amount_above_block_threshold");
        }
        if (properties.reviewThreshold() > 0 && amount > properties.reviewThreshold()) {
            return RiskDecision.of(RiskDecision.Outcome.REVIEW, "amount_above_review_threshold");
        }
        return RiskDecision.allow();
    }
}
