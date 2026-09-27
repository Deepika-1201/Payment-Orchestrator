package com.payments.gateway.provider.razorpay;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.support.IntegrationTest;
import com.payments.gateway.support.StubPsp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** The gateway end to end with the Razorpay adapter enabled, against a local stub of Razorpay's API (ADR-030). */
class RazorpayGatewayIntegrationTest extends IntegrationTest {

    private static final StubPsp RAZORPAY = RazorpayPaymentProviderTest.razorpayStub();
    private static final String KEY_ID = "rzp_test_it0001";
    private static final String KEY_SECRET = "it_key_secret_0001";
    private static final String WEBHOOK_SECRET = "it_webhook_secret_0001";

    @DynamicPropertySource
    static void razorpay(DynamicPropertyRegistry registry) {
        registry.add("pg.providers.razorpay.enabled", () -> "true");
        registry.add("pg.providers.razorpay.base-url", () -> RAZORPAY.baseUrl().toString());
        registry.add("pg.providers.http.read-timeout", () -> "1s");
    }

    @AfterAll
    static void stopRazorpay() {
        RAZORPAY.close();
    }

    @BeforeEach
    void resetRazorpay() {
        RAZORPAY.reset();
    }

    private record Linked(TestMerchant merchant, String webhookPath) {
    }

    private Linked merchantOnRazorpay() {
        TestMerchant merchant = createMerchant();
        Response linked = admin("PUT", "/admin/v1/merchants/" + merchant.id() + "/provider-accounts/" + RazorpayApi.CODE,
                Map.of("credentials", Map.of("key_id", KEY_ID, "key_secret", KEY_SECRET, "webhook_secret", WEBHOOK_SECRET)));
        assertThat(linked.status()).as(linked.raw()).isEqualTo(200);
        assertThat(linked.raw()).doesNotContain(KEY_SECRET).doesNotContain(WEBHOOK_SECRET);
        return new Linked(merchant, str(linked.body(), "webhook_path"));
    }

    private Response webhook(Linked linked, String eventId, String body, String secret) {
        return send("POST", linked.webhookPath(), Map.of("X-Razorpay-Signature", Hashing.hmacSha256Hex(secret, body),
                "X-Razorpay-Event-Id", eventId), body);
    }

    @Test
    void aCardPaymentCompletesOnRazorpaysPageAndIsSettledAndRefundedThroughSignedWebhooks() {
        Linked linked = merchantOnRazorpay();
        RAZORPAY.on("POST /v1/payment_links", 200, "{\"id\":\"plink_it1\",\"short_url\":\"https://rzp.io/i/it1\",\"status\":\"created\"}");
        String paymentId = str(createPayment(linked.merchant(), 49_900, "automatic"), "id");

        Response confirmed = confirm(linked.merchant(), paymentId, card());

        assertThat(str(confirmed.body(), "status")).as(confirmed.raw()).isEqualTo("requires_action");
        assertThat(str(confirmed.body(), "next_action.type")).isEqualTo("redirect");
        assertThat(str(confirmed.body(), "next_action.url")).isEqualTo("https://rzp.io/i/it1");
        assertThat(str(confirmed.body(), "latest_attempt.provider")).isEqualTo(RazorpayApi.CODE);
        assertThat(str(confirmed.body(), "latest_attempt.provider_reference")).isEqualTo("plink_it1");
        String attemptId = str(confirmed.body(), "latest_attempt.id");
        StubPsp.Recorded created = RAZORPAY.last("POST /v1/payment_links");
        assertThat(created.authorization()).as("the merchant's own Razorpay keys")
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8)));
        assertThat(json.read(created.body(), JsonNode.class).path("reference_id").asString()).isEqualTo(attemptId);

        String paid = "{\"entity\":\"event\",\"event\":\"payment_link.paid\",\"payload\":{"
                + "\"payment_link\":{\"entity\":{\"id\":\"plink_it1\",\"reference_id\":\"" + attemptId + "\",\"order_id\":\"order_it1\","
                + "\"status\":\"paid\"}},\"payment\":{\"entity\":{\"id\":\"pay_it1\",\"order_id\":\"order_it1\",\"status\":\"captured\","
                + "\"amount\":49900,\"currency\":\"INR\",\"method\":\"card\",\"card\":{\"network\":\"Visa\",\"last4\":\"1111\"},"
                + "\"notes\":{\"pg_attempt_id\":\"" + attemptId + "\"}}}}}";
        assertThat(webhook(linked, "evt_it_forged", paid, "not_the_merchants_secret").status()).isEqualTo(401);
        assertThat(str(getPayment(linked.merchant(), paymentId), "status")).isEqualTo("requires_action");

        Response delivered = webhook(linked, "evt_it_paid", paid, WEBHOOK_SECRET);
        assertThat(delivered.status()).as(delivered.raw()).isEqualTo(200);
        assertThat(num(delivered.body(), "received")).isEqualTo(1);
        assertThat(num(webhook(linked, "evt_it_paid", paid, WEBHOOK_SECRET).body(), "duplicates")).as("Razorpay retries").isEqualTo(1);
        Map<String, Object> payment = getPayment(linked.merchant(), paymentId);
        assertThat(str(payment, "status")).isEqualTo("succeeded");
        assertThat(str(payment, "latest_attempt.card.network")).isEqualTo("visa");
        assertThat(str(payment, "latest_attempt.card.last4")).isEqualTo("1111");

        RAZORPAY.on("GET /v1/payment_links/plink_it1", 200, "{\"id\":\"plink_it1\",\"order_id\":\"order_it1\",\"status\":\"paid\"}")
                .on("GET /v1/orders/order_it1/payments", 200, "{\"items\":[{\"id\":\"pay_it1\",\"status\":\"captured\",\"amount\":49900,\"currency\":\"INR\"}]}")
                .on("POST /v1/payments/pay_it1/refund", 200, "{\"id\":\"rfnd_it1\",\"payment_id\":\"pay_it1\",\"status\":\"pending\","
                        + "\"amount\":10000,\"currency\":\"INR\"}");
        Response refund = post(linked.merchant(), "/v1/payments/" + paymentId + "/refunds", UUID.randomUUID().toString(),
                Map.of("amount", 10_000));
        assertThat(refund.status()).as(refund.raw()).isEqualTo(201);
        assertThat(str(refund.body(), "status")).isEqualTo("pending");
        String refundId = str(refund.body(), "id");
        assertThat(json.read(RAZORPAY.last("POST /v1/payments/pay_it1/refund").body(), JsonNode.class).path("receipt").asString())
                .isEqualTo(refundId);

        String processed = "{\"entity\":\"event\",\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{"
                + "\"id\":\"rfnd_it1\",\"payment_id\":\"pay_it1\",\"receipt\":\"" + refundId + "\",\"status\":\"processed\","
                + "\"amount\":10000,\"currency\":\"INR\",\"notes\":{\"pg_refund_id\":\"" + refundId + "\"}}}}}";
        assertThat(webhook(linked, "evt_it_refund", processed, WEBHOOK_SECRET).status()).isEqualTo(200);
        Map<String, Object> refunded = list(get(linked.merchant(), "/v1/payments/" + paymentId + "/refunds").body(), "data").getFirst();
        assertThat(str(refunded, "status")).isEqualTo("succeeded");
    }

    @Test
    void aTimedOutLinkCreationIsNeverRetriedButFoundByItsReferenceAndSettled() {
        Linked linked = merchantOnRazorpay();
        RAZORPAY.slow("POST /v1/payment_links", 2_500);
        String paymentId = str(createPayment(linked.merchant(), 25_000, "automatic"), "id");

        Response confirmed = confirm(linked.merchant(), paymentId, card());

        assertThat(str(confirmed.body(), "latest_attempt.status")).as(confirmed.raw()).isEqualTo("unknown");
        String attemptId = str(confirmed.body(), "latest_attempt.id");
        RAZORPAY.on("GET /v1/payment_links", 200, "{\"payment_links\":[{\"id\":\"plink_late\",\"reference_id\":\"" + attemptId
                        + "\",\"order_id\":\"order_late\",\"status\":\"paid\"}]}")
                .on("GET /v1/orders/order_late/payments", 200, "{\"items\":[{\"id\":\"pay_late\",\"status\":\"captured\","
                        + "\"amount\":25000,\"currency\":\"INR\"}]}");

        resolveStatuses(Duration.ofSeconds(6), 2);

        Map<String, Object> payment = getPayment(linked.merchant(), paymentId);
        assertThat(str(payment, "status")).isEqualTo("succeeded");
        assertThat(str(payment, "latest_attempt.provider_reference")).as("learnt from the lookup").isEqualTo("plink_late");
        assertThat(RAZORPAY.requests()).filteredOn(r -> r.method().equals("POST") && r.path().equals("/v1/payment_links"))
                .as("a link that may exist is never created twice").hasSize(1);
        assertThat(RAZORPAY.last("GET /v1/payment_links").query()).isEqualTo("reference_id=" + attemptId);
    }
}
