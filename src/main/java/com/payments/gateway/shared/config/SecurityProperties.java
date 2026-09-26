package com.payments.gateway.shared.config;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code apiKeyMode} is per environment (ADR-014): a sandbox deployment issues {@code sk_test_} keys and talks to PSP
 * sandboxes, production issues {@code sk_live_} keys. Test and live data never share a database.
 */
@ConfigurationProperties("pg.security")
public record SecurityProperties(List<String> adminTokens, String dataEncryptionKey, ApiKeyMode apiKeyMode) {

    public enum ApiKeyMode {
        TEST,
        LIVE;

        public String prefix() {
            return "sk_" + name().toLowerCase(Locale.ROOT) + "_";
        }
    }

    public SecurityProperties {
        adminTokens = adminTokens == null ? List.of() : adminTokens.stream().filter(t -> t != null && !t.isBlank()).toList();
        apiKeyMode = apiKeyMode == null ? ApiKeyMode.TEST : apiKeyMode;
    }
}
