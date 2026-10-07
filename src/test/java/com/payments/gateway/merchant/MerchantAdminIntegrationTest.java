package com.payments.gateway.merchant;

import com.payments.gateway.support.FakeMerchantEndpoint;
import com.payments.gateway.support.IntegrationTest;
import com.payments.gateway.webhook.outbound.WebhookSigner;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

class MerchantAdminIntegrationTest extends IntegrationTest {

    @Test
    void settingsChangesApplyToNewPaymentsAndAreAuditedWithoutTheWebhookUrl() {
        TestMerchant merchant = createMerchant(ALPHA);
        String path = "/admin/v1/merchants/" + merchant.id();

        Response updated = admin("PATCH", path, Map.of("name", "Renamed Store", "late_success_policy", "accept",
                "payment_expiry_seconds", 600, "webhook_url", "https://merchant.example/hooks?token=abc"));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(str(updated.body(), "name")).isEqualTo("Renamed Store");
        assertThat(str(updated.body(), "late_success_policy")).isEqualTo("accept");
        assertThat(num(updated.body(), "payment_expiry_seconds")).isEqualTo(600);
        Map<String, Object> payment = createPayment(merchant, 10_000, "automatic");
        assertThat(Duration.between(Instant.parse(str(payment, "created_at")), Instant.parse(str(payment, "expires_at"))))
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(admin("DELETE", path + "/webhook-url", null).body()).doesNotContainKey("webhook_url");
        List<String> audit = jdbc.sql("SELECT action || ' ' || details::text FROM audit_log WHERE resource_id = ? ORDER BY occurred_at")
                .param(1, merchant.id()).query(String.class).list();
        assertThat(audit).anyMatch(entry -> entry.startsWith("merchant.updated") && entry.contains("ACCEPT"))
                .anyMatch(entry -> entry.startsWith("merchant.webhook_url_removed"))
                .noneMatch(entry -> entry.contains("token=abc"));
        assertThat(admin("PATCH", path, Map.of("payment_expiry_seconds", 5)).status()).isEqualTo(400);
        assertThat(admin("PATCH", path, Map.of("late_success_policy", "sometimes")).status()).isEqualTo(400);
    }

    @Test
    void aMerchantWithoutAWebhookUrlChangesItsMandateDebitLimit() {
        TestMerchant merchant = createMerchant(ALPHA);
        String path = "/admin/v1/merchants/" + merchant.id();

        Response updated = admin("PATCH", path, Map.of("mandate_debit_limit", 2_000_000));

        assertThat(updated.status()).as(updated.raw()).isEqualTo(200);
        assertThat(updated.body()).doesNotContainKey("webhook_url");
        assertThat(num(updated.body(), "mandate_debit_limit")).isEqualTo(2_000_000);
        assertThat(admin("PATCH", path, Map.of("mandate_debit_limit", 10_000_001)).status()).as("above ₹1,00,000")
                .isEqualTo(400);
        assertThat(jdbc.sql("SELECT details::text FROM audit_log WHERE resource_id = ? AND action = 'merchant.updated'")
                .param(1, merchant.id()).query(String.class).single()).contains("\"mandate_debit_limit\": 2000000");
    }

    @Test
    void suspensionBlocksKeysAndCheckoutLinksWhilePaymentsInFlightStillComplete() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, upi("intent"));
        String otherPayment = str(createPayment(merchant, 20_000, "automatic"), "id");
        String checkout = URI.create(str(post(merchant, "/v1/checkout-sessions", UUID.randomUUID().toString(),
                Map.of("payment_id", otherPayment)).body(), "url")).getPath();
        String path = "/admin/v1/merchants/" + merchant.id();

        assertThat(admin("POST", path + "/suspend", Map.of()).status()).isEqualTo(400);
        Response suspended = admin("POST", path + "/suspend", Map.of("reason", "KYC review"));

        assertThat(str(suspended.body(), "status")).isEqualTo("suspended");
        assertThat(str(suspended.body(), "status_reason")).isEqualTo("KYC review");
        assertThat(get(merchant, "/v1/payments/" + paymentId).status()).isEqualTo(401);
        assertThat(send("GET", checkout, Map.of(), null).status()).isEqualTo(404);
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);

        Response reactivated = admin("POST", path + "/reactivate", null);
        assertThat(str(reactivated.body(), "status")).isEqualTo("active");
        assertThat(reactivated.body()).doesNotContainKey("status_reason");
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
    }

    @Test
    void apiKeysRotateWithoutDowntimeAndRevocationIsImmediate() {
        TestMerchant merchant = createMerchant(ALPHA);
        String path = "/admin/v1/merchants/" + merchant.id() + "/api-keys";
        Response issued = admin("POST", path, null);
        String newKeyId = str(issued.body(), "id");
        TestMerchant rotated = new TestMerchant(merchant.id(), str(issued.body(), "api_key"), merchant.webhookSecret());
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");

        assertThat(rotated.apiKey()).startsWith("sk_test_");
        assertThat(str(issued.body(), "mode")).isEqualTo("test");
        assertThat(get(rotated, "/v1/payments/" + paymentId).status()).isEqualTo(200);
        Response keys = admin("GET", path, null);
        assertThat(list(keys.body(), "data")).hasSize(2).allSatisfy(key -> {
            assertThat(key).doesNotContainKey("api_key").containsKey("last_used_at");
            assertThat(str(key, "status")).isEqualTo("active");
        });
        assertThat(keys.raw()).doesNotContain(merchant.apiKey()).doesNotContain(rotated.apiKey());

        String oldKeyId = list(keys.body(), "data").stream().map(key -> str(key, "id"))
                .filter(id -> !id.equals(newKeyId)).findFirst().orElseThrow();
        Response revoked = admin("POST", path + "/" + oldKeyId + "/revoke", null);

        assertThat(str(revoked.body(), "status")).isEqualTo("revoked");
        assertThat(revoked.body()).containsKey("revoked_at");
        assertThat(get(merchant, "/v1/payments/" + paymentId).status()).isEqualTo(401);
        assertThat(get(rotated, "/v1/payments/" + paymentId).status()).isEqualTo(200);
        assertThat(admin("POST", path + "/" + oldKeyId + "/revoke", null).status()).isEqualTo(200);
        TestMerchant stranger = createMerchant(ALPHA);
        assertThat(admin("POST", "/admin/v1/merchants/" + stranger.id() + "/api-keys/" + newKeyId + "/revoke", null).status())
                .isEqualTo(404);
        assertThat(get(rotated, "/v1/payments/" + paymentId).status()).isEqualTo(200);
    }

    @Test
    void webhookSecretRotationSignsWithBothSecretsUntilTheGracePeriodEnds() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            String path = "/admin/v1/merchants/" + merchant.id() + "/webhook-secret";
            Response rotated = admin("POST", path, Map.of("previous_valid_for_seconds", 3600));
            String newSecret = str(rotated.body(), "webhook_secret");

            assertThat(newSecret).startsWith("whsec_").isNotEqualTo(merchant.webhookSecret());
            assertThat(Instant.parse(str(rotated.body(), "previous_secret_expires_at"))).isEqualTo(clock.instant().plusSeconds(3600));
            payAndSucceed(merchant, 10_000);
            deliveryWorker.deliverDue();
            assertThat(verifies(endpoint.received().getLast(), newSecret)).isTrue();
            assertThat(verifies(endpoint.received().getLast(), merchant.webhookSecret())).isTrue();

            clock.advance(Duration.ofHours(2));
            payAndSucceed(merchant, 20_000);
            deliveryWorker.deliverDue();
            assertThat(verifies(endpoint.received().getLast(), newSecret)).isTrue();
            assertThat(verifies(endpoint.received().getLast(), merchant.webhookSecret())).isFalse();

            assertThat(admin("POST", path, Map.of("previous_valid_for_seconds", 0)).body())
                    .doesNotContainKey("previous_secret_expires_at");
            assertThat(count("SELECT count(*) FROM merchants WHERE id = ? AND previous_webhook_secret_enc IS NULL", merchant.id()))
                    .isEqualTo(1);
        }
    }

    private boolean verifies(FakeMerchantEndpoint.Received delivery, String secret) {
        return WebhookSigner.verify(delivery.headers().get("pg-signature"), secret, delivery.body(),
                clock.instant().getEpochSecond(), 300);
    }
}
