package com.payments.gateway.risk.rules;

import com.payments.gateway.risk.RiskContext;
import com.payments.gateway.risk.RiskDecision;
import com.payments.gateway.risk.RiskProperties;
import com.payments.gateway.risk.RiskRule;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BlocklistRule implements RiskRule {

    private final RiskProperties properties;

    public BlocklistRule(RiskProperties properties) {
        this.properties = properties;
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (contains(properties.blockedVpas(), context.method().vpa())) {
            return RiskDecision.of(RiskDecision.Outcome.BLOCK, "vpa_blocklisted");
        }
        if (contains(properties.blockedIps(), context.ip())) {
            return RiskDecision.of(RiskDecision.Outcome.BLOCK, "ip_blocklisted");
        }
        if (contains(properties.blockedEmails(), context.customerEmail())) {
            return RiskDecision.of(RiskDecision.Outcome.BLOCK, "email_blocklisted");
        }
        if (context.customerReference() != null && properties.blockedCustomerReferences().contains(context.customerReference())) {
            return RiskDecision.of(RiskDecision.Outcome.BLOCK, "customer_blocklisted");
        }
        return RiskDecision.allow();
    }

    private static boolean contains(Set<String> blocked, String value) {
        return value != null && blocked.contains(value.toLowerCase(Locale.ROOT));
    }
}
