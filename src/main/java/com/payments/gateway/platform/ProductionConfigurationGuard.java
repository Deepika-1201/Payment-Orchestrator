package com.payments.gateway.platform;

import com.payments.gateway.checkout.CheckoutProperties;
import com.payments.gateway.merchant.web.RateLimitProperties;
import com.payments.gateway.shared.config.OutboundWebhookProperties;
import com.payments.gateway.shared.config.SecurityProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the {@code prod} profile with settings that are only safe for development (ADR-022): simulated
 * PSPs, webhook delivery to private addresses, plain HTTP, disabled rate limits, or a data key published in this
 * repository.
 */
@Component
@Profile("prod")
public class ProductionConfigurationGuard {

    /** Keys committed for local development and tests; they protect nothing. */
    static final Set<String> PUBLIC_DEVELOPMENT_KEYS = Set.of("bG9jYWwtZGV2LWtleS0wMDAwMDAwMDAwMDAwMDAwMDA=");

    public ProductionConfigurationGuard(SecurityProperties security, Environment environment,
                                        OutboundWebhookProperties webhooks, RateLimitProperties rateLimits,
                                        CheckoutProperties checkout) {
        boolean mockProviders = environment.getProperty("pg.providers.mock.enabled", Boolean.class, false);
        List<String> problems = check(security, mockProviders, webhooks, rateLimits, checkout);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration: " + String.join("; ", problems));
        }
    }

    static List<String> check(SecurityProperties security, boolean mockProviders, OutboundWebhookProperties webhooks,
                              RateLimitProperties rateLimits, CheckoutProperties checkout) {
        List<String> problems = new ArrayList<>();
        if (mockProviders) {
            problems.add("pg.providers.mock.enabled must be false");
        }
        if (webhooks.allowPrivateTargets()) {
            problems.add("pg.webhooks.outbound.allow-private-targets must be false");
        }
        if (!webhooks.requireHttps()) {
            problems.add("pg.webhooks.outbound.require-https must be true");
        }
        if (!rateLimits.enabled()) {
            problems.add("pg.rate-limit.enabled must be true");
        }
        String key = security.dataEncryptionKey();
        if (key == null || key.isBlank() || PUBLIC_DEVELOPMENT_KEYS.contains(key.trim())) {
            problems.add("pg.security.data-encryption-key must be a private key from Secrets Manager");
        }
        if (security.adminUsers().isEmpty() && security.adminTokens().isEmpty() && !security.oidc().enabled()) {
            problems.add("configure pg.security.admin-users or pg.security.oidc (ADR-019, ADR-023)");
        }
        if (checkout.publicBaseUrl() == null || !checkout.publicBaseUrl().startsWith("https://")) {
            problems.add("pg.checkout.public-base-url must be an https:// URL");
        }
        return problems;
    }
}
