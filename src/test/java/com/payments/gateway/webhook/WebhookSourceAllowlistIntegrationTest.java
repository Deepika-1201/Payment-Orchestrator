package com.payments.gateway.webhook;

import com.payments.gateway.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** PSP webhook source allowlists (ADR-022); the test client is a trusted proxy, so X-Forwarded-For sets the source. */
@TestPropertySource(properties = "pg.webhooks.inbound.allowed-sources.MOCK_BETA=203.0.113.0/24,2001:db8::/32")
class WebhookSourceAllowlistIntegrationTest extends IntegrationTest {

    @Test
    void onlyAllowlistedSourcesReachTheInboxOfThatProvider() {
        Response direct = send("POST", "/v1/webhooks/providers/" + BETA, Map.of(), "{}");
        assertThat(direct.status()).isEqualTo(403);
        assertThat(str(direct.body(), "code")).isEqualTo("forbidden");

        Response allowed = send("POST", "/v1/webhooks/providers/" + BETA, Map.of("X-Forwarded-For", "203.0.113.7"), "{}");
        assertThat(allowed.status()).as("allowed source, then the signature is checked").isEqualTo(401);
        Response ipv6 = send("POST", "/v1/webhooks/providers/" + BETA + "/pma_any", Map.of("X-Forwarded-For", "2001:db8::10"), "{}");
        assertThat(ipv6.status()).as("allowed source, unknown account").isEqualTo(404);

        assertThat(send("POST", "/v1/webhooks/providers/" + ALPHA, Map.of(), "{}").status()).as("no allowlist").isEqualTo(401);
        assertThat(count("SELECT count(*) FROM provider_webhook_events")).isZero();
    }
}
