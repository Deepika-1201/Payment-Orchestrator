package com.payments.gateway.risk.rules;

import com.payments.gateway.risk.RiskContext;
import com.payments.gateway.risk.RiskDecision;
import com.payments.gateway.risk.RiskProperties;
import com.payments.gateway.risk.RiskRule;
import org.springframework.stereotype.Component;

@Component
public class VelocityRule implements RiskRule {

    private final RiskProperties properties;

    public VelocityRule(RiskProperties properties) {
        this.properties = properties;
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.customerReference() != null && properties.maxAttemptsPerCustomer() > 0
                && context.recentAttemptsByCustomer() >= properties.maxAttemptsPerCustomer()) {
            return RiskDecision.of(RiskDecision.Outcome.BLOCK, "customer_velocity_exceeded");
        }
        return RiskDecision.allow();
    }
}
