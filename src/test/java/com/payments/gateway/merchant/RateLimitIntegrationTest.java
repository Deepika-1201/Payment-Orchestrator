package com.payments.gateway.merchant;

import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** Tiny budgets and the frozen test clock make throttling deterministic; tokens come back only when time moves. */
@TestPropertySource(properties = {
        "pg.rate-limit.write.per-second=1", "pg.rate-limit.write.burst=3",
        "pg.rate-limit.read.per-second=1", "pg.rate-limit.read.burst=2"})
class RateLimitIntegrationTest extends IntegrationTest {

    private static final Map<String, Object> PAYMENT = Map.of("amount", 10_000, "currency", "INR", "merchant_order_id", "o1");

    @Test
    void writesOverBudgetGet429WithRetryAfterUntilTokensRefill() {
        TestMerchant merchant = createMerchant(ALPHA);
        for (int i = 0; i < 3; i++) {
            assertThat(post(merchant, "/v1/payments", "k" + i, PAYMENT).status()).isEqualTo(201);
        }

        Response limited = assertContract("POST", "/v1/payments", PAYMENT, post(merchant, "/v1/payments", "k3", PAYMENT));

        assertThat(limited.status()).isEqualTo(429);
        assertThat(str(limited.body(), "code")).isEqualTo("rate_limited");
        assertThat(limited.headers().firstValue("Retry-After")).contains("1");
        clock.advance(Duration.ofSeconds(1));
        assertThat(post(merchant, "/v1/payments", "k3", PAYMENT).status()).isEqualTo(201);
    }

    @Test
    void aThrottledRequestDoesNotConsumeItsIdempotencyKey() {
        TestMerchant merchant = createMerchant(ALPHA);
        for (int i = 0; i < 3; i++) {
            post(merchant, "/v1/payments", "fill" + i, PAYMENT);
        }
        assertThat(post(merchant, "/v1/payments", "retry-me", PAYMENT).status()).isEqualTo(429);

        clock.advance(Duration.ofSeconds(1));
        Response retried = post(merchant, "/v1/payments", "retry-me", PAYMENT);

        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.headers().firstValue("Idempotent-Replayed")).contains("false");
    }

    @Test
    void budgetsAreSeparatePerMerchantAndForReadsAndWrites() {
        TestMerchant noisy = createMerchant(ALPHA);
        TestMerchant quiet = createMerchant(ALPHA);
        String paymentId = null;
        for (int i = 0; i < 3; i++) {
            paymentId = str(post(noisy, "/v1/payments", "n" + i, PAYMENT).body(), "id");
        }
        assertThat(post(noisy, "/v1/payments", "n3", PAYMENT).status()).isEqualTo(429);

        assertThat(get(noisy, "/v1/payments/" + paymentId).status()).isEqualTo(200);
        assertThat(get(noisy, "/v1/payments/" + paymentId).status()).isEqualTo(200);
        assertThat(get(noisy, "/v1/payments/" + paymentId).status()).isEqualTo(429);
        assertThat(post(quiet, "/v1/payments", "q0", PAYMENT).status()).isEqualTo(201);
    }

    @Test
    void unauthenticatedRequestsAndPspWebhooksAreNotCounted() {
        TestMerchant merchant = createMerchant(ALPHA);
        for (int i = 0; i < 5; i++) {
            assertThat(send("POST", "/v1/webhooks/providers/" + ALPHA, Map.of(), "{}").status()).isNotEqualTo(429);
            assertThat(send("POST", "/v1/payments", Map.of("Authorization", "Bearer sk_test_invalid"), PAYMENT).status())
                    .isEqualTo(401);
        }

        assertThat(post(merchant, "/v1/payments", "w0", PAYMENT).status()).isEqualTo(201);
    }
}
