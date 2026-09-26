package com.payments.gateway.risk;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.risk")
public record RiskProperties(long reviewThreshold, long blockThreshold, Duration velocityWindow,
                             int maxAttemptsPerCustomer, Set<String> blockedVpas, Set<String> blockedIps,
                             Set<String> blockedEmails, Set<String> blockedCustomerReferences, External external) {

    public RiskProperties {
        velocityWindow = velocityWindow == null ? Duration.ofMinutes(10) : velocityWindow;
        blockedVpas = normalize(blockedVpas);
        blockedIps = normalize(blockedIps);
        blockedEmails = normalize(blockedEmails);
        blockedCustomerReferences = blockedCustomerReferences == null ? Set.of() : Set.copyOf(blockedCustomerReferences);
        external = external == null ? new External(null, null, null) : external;
    }

    /**
     * An optional external fraud-scoring service (ADR-016). Enabled when {@code url} is set; requests are signed with
     * {@code secret} and bounded by {@code timeout}, after which the payment goes to manual review.
     */
    public record External(URI url, String secret, Duration timeout) {

        public External {
            timeout = timeout == null ? Duration.ofMillis(800) : timeout;
        }
    }

    private static Set<String> normalize(Set<String> values) {
        return values == null ? Set.of()
                : values.stream().map(v -> v.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
