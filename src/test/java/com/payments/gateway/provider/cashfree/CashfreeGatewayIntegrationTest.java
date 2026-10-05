package com.payments.gateway.provider.cashfree;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

import com.payments.gateway.support.IntegrationTest;
import com.payments.gateway.support.StubPsp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** The gateway end to end with the Cashfree adapter enabled, against a local stub of Cashfree's API (ADR-031). */
class CashfreeGatewayIntegrationTest extends IntegrationTest {

    private static final StubPsp CASHFREE = CashfreePaymentProviderTest.cashfreeStub();
    private static final String CLIENT_ID = "cf_it_app_0001";
    private static final String CLIENT_SECRET = "cfsk_it_secret_0001";

    @DynamicPropertySource
    static void cashfree(DynamicPropertyRegistry registry) {
        registry.add("pg.providers.cashfree.enabled", () -> "true");
        registry.add("pg.providers.cashfree.base-url", () -> CASHFREE.baseUrl().toString());
        registry.add("pg.providers.http.read-timeout", () -> "1s");
    }

    @AfterAll
    static void stopCashfree() {
        CASHFREE.close();
    }

    @BeforeEach
    void resetCashfree() {
        CASHFREE.reset();
    }

    private record Linked(TestMerchant merchant, String webhookPath) {
    }

    private Linked merchantOnCashfree(String... otherProviders) {
        TestMerchant merchant = createMerchant(otherProviders);
        Response linked = admin("PUT", "/admin/v1/merchants/" + merchant.id() + "/provider-accounts/" + CashfreeApi.CODE,
                Map.of("credentials", Map.of("client_id", CLIENT_ID, "client_secret", CLIENT_SECRET)));
        assertThat(linked.status()).as(linked.raw()).isEqualTo(200);
        assertThat(linked.raw()).doesNotContain(CLIENT_SECRET);
        return new Linked(merchant, str(linked.body(), "webhook_path"));
    }

    private String paymentWithPhone(TestMerchant merchant, long amount, String phone) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("reference", "cust_" + UUID.randomUUID().toString().substring(0, 8));
        customer.put("email", "buyer@example.com");
        if (phone != null) {
            customer.put("phone", phone);
        }
        Response created = post(merchant, "/v1/payments", UUID.randomUUID().toString(), Map.of("amount", amount,
                "currency", "INR", "merchant_order_id", "order_" + UUID.randomUUID().toString().substring(0, 8),
                "capture_method", "automatic", "customer", customer));
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        return str(created.body(), "id");
    }

    private Response webhook(Linked linked, String body, String secret) {
        String timestamp = String.valueOf(clock.instant().toEpochMilli());
        return send("POST", linked.webhookPath(), Map.of("x-webhook-signature",
                CashfreePaymentProvider.signature(secret, timestamp, body), "x-webhook-timestamp", timestamp,
                "x-webhook-version", "2025-01-01"), body);
    }

    @Test
    void routingSkipsCashfreeForPaymentsWithoutACustomerPhone() {
        Linked onlyCashfree = merchantOnCashfree();
        Response refused = confirm(onlyCashfree.merchant(), paymentWithPhone(onlyCashfree.merchant(), 10_000, null), card());
        assertThat(refused.status()).isEqualTo(422);
        assertThat(str(refused.body(), "code")).isEqualTo("unsupported_payment_method");

        Linked withAlpha = merchantOnCashfree(ALPHA);
        Response routed = confirm(withAlpha.merchant(), paymentWithPhone(withAlpha.merchant(), 10_000, null), card());
        assertThat(str(routed.body(), "latest_attempt.provider")).as(routed.raw()).isEqualTo(ALPHA);
        assertThat(CASHFREE.requests()).isEmpty();
    }

    @Test
    void aCardPaymentOnCashfreesLinkIsSettledAndRefundedThroughSignedWebhooks() {
        Linked linked = merchantOnCashfree();
        CASHFREE.on("POST /pg/links", 200, "{\"cf_link_id\":\"555001\",\"link_id\":\"x\",\"link_status\":\"ACTIVE\","
                + "\"link_url\":\"https://payments-test.cashfree.com/links/it1\"}");
        String paymentId = paymentWithPhone(linked.merchant(), 49_901, "+919000090000");

        Response confirmed = confirm(linked.merchant(), paymentId, card());

        assertThat(str(confirmed.body(), "status")).as(confirmed.raw()).isEqualTo("requires_action");
        assertThat(str(confirmed.body(), "next_action.url")).isEqualTo("https://payments-test.cashfree.com/links/it1");
        assertThat(str(confirmed.body(), "latest_attempt.provider_reference")).isEqualTo("cflink_555001");
        String attemptId = str(confirmed.body(), "latest_attempt.id");
        StubPsp.Recorded created = CASHFREE.last("POST /pg/links");
        assertThat(created.header("x-client-id")).as("the merchant's own Cashfree app").isEqualTo(CLIENT_ID);
        JsonNode link = json.read(created.body(), JsonNode.class);
        assertThat(link.path("link_id").asString()).isEqualTo(attemptId);
        assertThat(link.path("link_amount").decimalValue()).isEqualByComparingTo("499.01");

        String success = "{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"event_time\":\"2026-09-27T15:30:00+05:30\",\"data\":{\"order\":"
                + "{\"order_id\":\"CFPay_it1_abc\",\"order_amount\":499.01,\"order_currency\":\"INR\",\"order_tags\":"
                + "{\"cf_link_id\":\"555001\"}},\"payment\":{\"cf_payment_id\":\"9001\",\"payment_status\":\"SUCCESS\","
                + "\"payment_amount\":499.01,\"payment_currency\":\"INR\",\"payment_method\":{\"card\":"
                + "{\"card_number\":\"XXXXXXXXXXXX1111\",\"card_network\":\"visa\"}}}}}";
        assertThat(webhook(linked, success, "not_the_merchants_secret").status()).isEqualTo(401);
        Response delivered = webhook(linked, success, CLIENT_SECRET);
        assertThat(delivered.status()).as(delivered.raw()).isEqualTo(200);
        assertThat(num(webhook(linked, success, CLIENT_SECRET).body(), "duplicates")).as("a retry, even re-timestamped").isEqualTo(1);
        Map<String, Object> payment = getPayment(linked.merchant(), paymentId);
        assertThat(str(payment, "status")).isEqualTo("succeeded");
        assertThat(str(payment, "latest_attempt.card.last4")).isEqualTo("1111");

        CASHFREE.on("GET /pg/links/" + attemptId + "/orders", 200, "[{\"order_id\":\"CFPay_it1_abc\",\"order_status\":\"PAID\"}]")
                .on("POST /pg/orders/CFPay_it1_abc/refunds", 200, "{\"cf_refund_id\":\"77001\",\"refund_amount\":100.00,"
                        + "\"refund_currency\":\"INR\",\"refund_status\":\"PENDING\"}");
        Response refund = post(linked.merchant(), "/v1/payments/" + paymentId + "/refunds", UUID.randomUUID().toString(),
                Map.of("amount", 10_000));
        assertThat(refund.status()).as(refund.raw()).isEqualTo(201);
        String refundId = str(refund.body(), "id");
        assertThat(json.read(CASHFREE.last("POST /pg/orders/CFPay_it1_abc/refunds").body(), JsonNode.class)
                .path("refund_id").asString()).isEqualTo(refundId);

        String processed = "{\"type\":\"REFUND_STATUS_WEBHOOK\",\"data\":{\"refund\":{\"cf_refund_id\":77001,\"refund_id\":\""
                + refundId + "\",\"order_id\":\"CFPay_it1_abc\",\"refund_amount\":100.00,\"refund_currency\":\"INR\","
                + "\"refund_status\":\"SUCCESS\"}}}";
        assertThat(webhook(linked, processed, CLIENT_SECRET).status()).isEqualTo(200);
        assertThat(str(list(get(linked.merchant(), "/v1/payments/" + paymentId + "/refunds").body(), "data").getFirst(), "status"))
                .isEqualTo("succeeded");
    }

    @Test
    void aTimedOutLinkCreationIsFoundByTheAttemptIdAndSettled() {
        Linked linked = merchantOnCashfree();
        CASHFREE.slow("POST /pg/links", 2_500);
        String paymentId = paymentWithPhone(linked.merchant(), 25_000, "9000090000");

        Response confirmed = confirm(linked.merchant(), paymentId, card());

        assertThat(str(confirmed.body(), "latest_attempt.status")).as(confirmed.raw()).isEqualTo("unknown");
        String attemptId = str(confirmed.body(), "latest_attempt.id");
        CASHFREE.on("GET /pg/links/" + attemptId, 200, "{\"cf_link_id\":\"555002\",\"link_id\":\"" + attemptId
                        + "\",\"link_status\":\"PAID\",\"link_currency\":\"INR\",\"link_amount_paid\":250.00}")
                .on("GET /pg/links/" + attemptId + "/orders", 200, "[{\"order_id\":\"CFPay_it2\",\"order_status\":\"PAID\"}]")
                .on("GET /pg/orders/CFPay_it2/payments", 200, "[{\"payment_status\":\"SUCCESS\",\"payment_amount\":250.00,"
                        + "\"payment_currency\":\"INR\"}]");

        resolveStatuses(Duration.ofSeconds(6), 2);

        Map<String, Object> payment = getPayment(linked.merchant(), paymentId);
        assertThat(str(payment, "status")).isEqualTo("succeeded");
        assertThat(str(payment, "latest_attempt.provider_reference")).isEqualTo("cflink_555002");
        assertThat(CASHFREE.requests()).filteredOn(r -> r.method().equals("POST") && r.path().equals("/pg/links"))
                .as("never created twice").hasSize(1);
    }

    private static String reconEvent(String type, String id, String saleType, String amount, String settled,
                                     Instant settledAt, String order, String extra) {
        return "{\"event_details\":{\"entity\":\"recon\",\"event_id\":\"" + id + "\",\"event_type\":\"" + type
                + "\",\"sale_type\":\"" + saleType + "\",\"event_status\":\"SUCCESS\",\"event_amount\":" + amount
                + ",\"event_settlement_amount\":" + settled + ",\"event_currency\":\"INR\"},\"order_details\":" + order
                + ",\"settlement_details\":{\"cf_settlement_id\":\"88001\",\"settlement_date\":\""
                + OffsetDateTime.ofInstant(settledAt.truncatedTo(ChronoUnit.SECONDS), ZoneOffset.ofHoursMinutes(5, 30))
                + "\",\"utr\":\"CB88001\"}" + extra + "}";
    }

    @Test
    void cashfreesSettlementReconciliationMatchesWebhookOutcomesAndFlagsARiskHold() {
        Linked linked = merchantOnCashfree();
        CASHFREE.on("POST /pg/links", 200, "{\"cf_link_id\":\"555010\",\"link_status\":\"ACTIVE\","
                + "\"link_url\":\"https://payments-test.cashfree.com/links/rc1\"}");
        String paymentId = paymentWithPhone(linked.merchant(), 49_901, "9000090000");
        String attemptId = str(confirm(linked.merchant(), paymentId, card()).body(), "latest_attempt.id");
        String linkOrder = "{\"order_id\":\"CFPay_rc1\",\"order_tags\":{\"cf_link_id\":\"555010\"}}";
        assertThat(webhook(linked, "{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":" + linkOrder
                + ",\"payment\":{\"payment_status\":\"SUCCESS\",\"payment_amount\":499.01,\"payment_currency\":\"INR\"}}}",
                CLIENT_SECRET).status()).isEqualTo(200);
        CASHFREE.on("GET /pg/links/" + attemptId + "/orders", 200, "[{\"order_id\":\"CFPay_rc1\",\"order_status\":\"PAID\"}]")
                .on("POST /pg/orders/CFPay_rc1/refunds", 200, "{\"cf_refund_id\":\"77010\",\"refund_amount\":100.00,"
                        + "\"refund_currency\":\"INR\",\"refund_status\":\"SUCCESS\"}")
                .on("GET /pg/orders/CFPay_rc1", 200, linkOrder);
        String refundId = str(post(linked.merchant(), "/v1/payments/" + paymentId + "/refunds", UUID.randomUUID().toString(),
                Map.of("amount", 10_000)).body(), "id");
        assertThat(webhook(linked, "{\"type\":\"DISPUTE_CREATED\",\"data\":{\"dispute\":{\"dispute_id\":\"433010\","
                + "\"dispute_type\":\"CHARGEBACK\",\"dispute_amount\":50.00,\"dispute_amount_currency\":\"INR\","
                + "\"dispute_status\":\"CHARGEBACK_CREATED\"},\"order_details\":{\"order_id\":\"CFPay_rc1\"}}}",
                CLIENT_SECRET).status()).isEqualTo(200);
        Instant now = clock.instant();
        CASHFREE.on("POST /pg/settlement/recon", 200, "{\"cursor\":null,\"limit\":1000,\"data\":["
                        + reconEvent("PAYMENT", "91", "CREDIT", "499.01", "487.23", now, linkOrder, "") + ","
                        + reconEvent("REFUND", "92", "DEBIT", "100.00", "100.00", now, linkOrder,
                                ",\"refund_details\":{\"refund_id\":\"" + refundId + "\"}") + ","
                        + reconEvent("CHARGEBACK", "93", "DEBIT", "50.00", "50.00", now, linkOrder, "") + ","
                        + reconEvent("RISK", "94", "DEBIT", "25.00", "25.00", now, "{}", "") + "]}")
                .on("GET /pg/orders/CFPay_rc1/disputes", 200, "[{\"dispute_id\":433010,\"dispute_amount\":50.00}]")
                .on("POST /pg/settlements", 200, "{\"cursor\":null,\"data\":[{\"cf_settlement_id\":\"88001\","
                        + "\"status\":\"SUCCESS\",\"amount_settled\":312.23,\"settlement_utr\":\"CB88001\"}]}");

        Response run = admin("POST", "/admin/v1/reconciliation/runs", Map.of("merchant_id", linked.merchant().id(),
                "provider", CashfreeApi.CODE, "from", now.minus(Duration.ofHours(1)).toString(),
                "to", now.plus(Duration.ofHours(1)).toString()));

        assertThat(run.status()).as(run.raw()).isEqualTo(201);
        assertThat(num(run.body(), "lines_total")).isEqualTo(4);
        assertThat(num(run.body(), "lines_matched")).as("capture, refund and chargeback").isEqualTo(3);
        assertThat(num(run.body(), "fee_amount")).isEqualTo(1_178);
        assertThat(num(run.body(), "adjustment_amount")).isEqualTo(-2_500);
        assertThat(num(run.body(), "settled_amount")).isEqualTo(31_223);
        assertThat(list(run.body(), "exceptions")).singleElement().satisfies(exception -> {
            assertThat(str(exception, "type")).isEqualTo("unmatched_adjustment");
            assertThat(str(exception, "reference")).isEqualTo("RISK:94");
            assertThat(num(exception, "actual_amount")).isEqualTo(-2_500);
        });
        assertThat(ledgerBalances(linked.merchant())).as("Cashfree holds 25.00 the gateway cannot explain yet")
                .containsEntry("psp_receivable", 2_500L)
                .containsEntry("bank_settlements", 31_223L);
    }
}
