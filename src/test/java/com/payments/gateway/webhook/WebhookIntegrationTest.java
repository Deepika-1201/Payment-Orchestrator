package com.payments.gateway.webhook;

import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.support.FakeMerchantEndpoint;
import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookIntegrationTest extends IntegrationTest {

    @Test
    void failingMerchantEndpointIsRetriedWithBackoffThenDeadLetteredAndReplayable() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            endpoint.respondWith(500);
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
            Response confirmed = confirm(merchant, paymentId, upi("intent"));
            simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);

            deliveryWorker.deliverDue();
            Map<String, Object> delivery = list(admin("GET", "/admin/v1/webhook-deliveries?resource_id=" + paymentId, null).body(), "data").getFirst();
            assertThat(str(delivery, "status")).isEqualTo("PENDING");
            assertThat(delivery.get("attempt_count")).isEqualTo(1);
            assertThat(delivery.get("last_response_status")).isEqualTo(500);

            deliveryWorker.deliverDue();
            assertThat(endpoint.received()).as("retry must wait for the backoff delay").hasSize(1);

            for (int i = 0; i < 12; i++) {
                clock.advance(Duration.ofHours(30));
                deliveryWorker.deliverDue();
            }
            delivery = list(admin("GET", "/admin/v1/webhook-deliveries?resource_id=" + paymentId, null).body(), "data").getFirst();
            assertThat(str(delivery, "status")).isEqualTo("DEAD");
            assertThat(delivery.get("attempt_count")).isEqualTo(10);
            assertThat(endpoint.received()).hasSize(10);

            endpoint.respondWith(200);
            assertThat(admin("POST", "/admin/v1/webhook-deliveries/" + str(delivery, "id") + "/replay", null).status()).isEqualTo(202);
            deliveryWorker.deliverDue();
            delivery = list(admin("GET", "/admin/v1/webhook-deliveries?resource_id=" + paymentId, null).body(), "data").getFirst();
            assertThat(str(delivery, "status")).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void providerWebhookWithBadSignatureIsRejectedAndNotStored() {
        Response response = send("POST", "/v1/webhooks/providers/" + ALPHA,
                Map.of("X-Mock-Signature", "t=" + clock.instant().getEpochSecond() + ",v1=deadbeef"),
                Map.of("event_id", "evt_forged", "type", "payment.updated", "status", "captured"));

        assertThat(response.status()).isEqualTo(401);
        assertThat(str(response.body(), "code")).isEqualTo("invalid_signature");
        assertThat(count("SELECT count(*) FROM provider_webhook_events")).isZero();
    }

    @Test
    void providerWebhookForUnknownPaymentIsStoredAndIgnored() {
        MockPaymentProvider alpha = mockProviders.stream().filter(p -> p.code().equals(ALPHA)).findFirst().orElseThrow();
        String body = json.write(Map.of("event_id", "evt_unknown", "type", "payment.updated",
                "provider_reference", "mock_alpha_unknown", "merchant_reference", "att_unknown", "status", "captured",
                "amount", 100, "currency", "INR"));

        Response response = send("POST", "/v1/webhooks/providers/" + ALPHA,
                Map.of("X-Mock-Signature", alpha.sign(clock.instant().getEpochSecond(), body)), body);

        assertThat(response.status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM provider_webhook_events").query(String.class).list()).containsExactly("IGNORED");
    }

    @Test
    void merchantEventsAreRecordedEvenWithoutWebhookUrl() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, upi("intent"));
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);

        List<String> types = jdbc.sql("SELECT type FROM merchant_events WHERE resource_id = ?").param(1, paymentId).query(String.class).list();

        assertThat(types).containsExactly("payment.succeeded");
        assertThat(count("SELECT count(*) FROM webhook_deliveries")).isZero();
    }
}
