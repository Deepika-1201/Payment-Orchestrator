package com.payments.gateway.risk;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.risk")
public record RiskProperties(long reviewThreshold, long blockThreshold, Duration velocityWindow,
                             int maxAttemptsPerCustomer, Set<String> blockedVpas, Set<String> blockedIps,
                             Set<String> blockedEmails, Set<String> blockedCustomerReferences) {

    public RiskProperties {
        velocityWindow = velocityWindow == null ? Duration.ofMinutes(10) : velocityWindow;
        blockedVpas = normalize(blockedVpas);
        blockedIps = normalize(blockedIps);
        blockedEmails = normalize(blockedEmails);
        blockedCustomerReferences = blockedCustomerReferences == null ? Set.of() : Set.copyOf(blockedCustomerReferences);
    }

    private static Set<String> normalize(Set<String> values) {
        return values == null ? Set.of() : values.stream().map(v -> v.toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
