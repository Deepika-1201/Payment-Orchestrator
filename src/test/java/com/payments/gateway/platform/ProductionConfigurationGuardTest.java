package com.payments.gateway.platform;

import com.payments.gateway.checkout.CheckoutProperties;
import com.payments.gateway.merchant.RateLimit;
import com.payments.gateway.merchant.web.RateLimitProperties;
import com.payments.gateway.shared.config.OutboundWebhookProperties;
import com.payments.gateway.shared.config.SecurityProperties;
import com.payments.gateway.shared.web.AdminRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationGuardTest {

    private static final SecurityProperties SECURE = new SecurityProperties(List.of(),
            List.of(new SecurityProperties.AdminUser("ops-asha", "a".repeat(64), Set.of(AdminRole.OPS))), null,
            "cHJvZHVjdGlvbi1rZXktZnJvbS1zZWNyZXRzLW1hbmFnZXI=", null, null, SecurityProperties.ApiKeyMode.LIVE);

    @Test
    void aProductionSetupPasses() {
        assertThat(ProductionConfigurationGuard.check(SECURE, false,
                new OutboundWebhookProperties(null, null, false, true), new RateLimitProperties(true, null, null),
                new CheckoutProperties("https://pay.example.com", null, null),
                "jdbc:postgresql://db.internal:5432/payments?sslmode=verify-full&sslrootcert=/app/certs/rds.pem"))
                .isEmpty();
    }

    @Test
    void developmentSettingsStopTheStart() {
        SecurityProperties devKey = new SecurityProperties(List.of(), List.of(), null, "bG9jYWwtZGV2LWtleS0wMDAwMDAwMDAwMDAwMDAwMDA=",
                null, null, SecurityProperties.ApiKeyMode.LIVE);
        MockEnvironment environment = new MockEnvironment().withProperty("pg.providers.mock.enabled", "true")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/payments?sslmode=require");

        assertThatThrownBy(() -> new ProductionConfigurationGuard(devKey, environment,
                new OutboundWebhookProperties(null, null, true, false),
                new RateLimitProperties(false, new RateLimit(1, 1), new RateLimit(1, 1)),
                new CheckoutProperties("http://localhost:8080", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pg.providers.mock.enabled")
                .hasMessageContaining("sslmode=verify-full")
                .hasMessageContaining("allow-private-targets")
                .hasMessageContaining("require-https")
                .hasMessageContaining("pg.rate-limit.enabled")
                .hasMessageContaining("data encryption keys")
                .hasMessageContaining("admin-users or pg.security.oidc")
                .hasMessageContaining("public-base-url");
    }
}
