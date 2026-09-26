package com.payments.gateway.merchant;

import com.payments.gateway.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityIntegrationTest extends IntegrationTest {

    @Test
    void merchantApiRequiresAValidKey() {
        Response missing = send("GET", "/v1/payments/pay_x", Map.of(), null);
        Response invalid = send("GET", "/v1/payments/pay_x", Map.of("Authorization", "Bearer sk_test_nope"), null);

        assertThat(missing.status()).isEqualTo(401);
        assertThat(str(missing.body(), "code")).isEqualTo("authentication_required");
        assertThat(invalid.status()).isEqualTo(401);
        assertThat(str(invalid.body(), "code")).isEqualTo("invalid_api_key");
    }

    @Test
    void merchantsCannotSeeEachOthersPayments() {
        TestMerchant owner = createMerchant(ALPHA);
        TestMerchant other = createMerchant(ALPHA);
        String paymentId = str(createPayment(owner, 10_000, "automatic"), "id");

        Response response = get(other, "/v1/payments/" + paymentId);

        assertThat(response.status()).isEqualTo(404);
        assertThat(str(response.body(), "code")).isEqualTo("resource_not_found");
    }

    @Test
    void adminApiRequiresAdminToken() {
        Response response = send("POST", "/admin/v1/merchants", Map.of("Authorization", "Bearer wrong"),
                Map.of("name", "x", "providers", List.of(ALPHA)));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void secretsAreNeverStoredInPlaintext() {
        TestMerchant merchant = createMerchantWith("https://merchant.example/hooks", null, ALPHA);

        String storedSecret = jdbc.sql("SELECT encode(webhook_secret_enc, 'escape') FROM merchants WHERE id = ?")
                .param(1, merchant.id()).query(String.class).single();
        String storedKeyHash = jdbc.sql("SELECT encode(key_hash, 'hex') FROM api_keys WHERE merchant_id = ?")
                .param(1, merchant.id()).query(String.class).single();

        assertThat(storedSecret).doesNotContain(merchant.webhookSecret());
        assertThat(storedKeyHash).doesNotContain(merchant.apiKey()).hasSize(64);
        assertThat(count("SELECT count(*) FROM audit_log WHERE resource_id = ?", merchant.id())).isEqualTo(2);
    }

    @Test
    void malformedWebhookUrlIsRejected() {
        Response response = admin("POST", "/admin/v1/merchants", Map.of("name", "x", "webhook_url", "ftp://example.com",
                "providers", List.of(ALPHA)));

        assertThat(response.status()).isEqualTo(400);
        assertThat(str(response.body(), "code")).isEqualTo("validation_error");
    }

    @Test
    void transitionLogIsAppendOnly() {
        TestMerchant merchant = createMerchant(ALPHA);
        createPayment(merchant, 10_000, "automatic");

        Throwable error = org.assertj.core.api.Assertions.catchThrowable(
                () -> jdbc.sql("DELETE FROM payment_transitions").update());

        assertThat(error).hasMessageContaining("append-only");
    }

    @Test
    void responsesCarryRequestIds() {
        Response response = send("GET", "/v1/payments/pay_x", Map.of("X-Request-Id", "trace-123"), null);

        assertThat(response.headers().firstValue("X-Request-Id")).contains("trace-123");
        assertThat(str(response.body(), "request_id")).isEqualTo("trace-123");
    }
}
