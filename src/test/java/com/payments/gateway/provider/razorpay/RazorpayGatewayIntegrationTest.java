package com.payments.gateway.provider.razorpay;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

import com.payments.gateway.payment.application.MandateScheduler;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.support.IntegrationTest;
import com.payments.gateway.support.StubPsp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
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
        registry.add("pg.providers.razorpay.settlement-lag", () -> "2d");
        registry.add("pg.providers.razorpay.mandates", () -> "true");
        registry.add("pg.providers.http.read-timeout", () -> "1s");
    }

    @Autowired
    private MandateScheduler mandateScheduler;

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

    // ------------------------------------------------------------------ mandates (ADR-035)

    @ParameterizedTest
    @ValueSource(strings = {"upi_autopay", "card", "enach"})
    void aMandateRegistersAndIsDebitedThroughRazorpay(String instrument) {
        boolean enach = instrument.equals("enach");
        Linked linked = merchantOnRazorpay();
        TestMerchant merchant = linked.merchant();
        RAZORPAY.on("POST /v1/subscription_registration/auth_links", 200, "{\"id\":\"inv_it1\",\"customer_id\":\"cust_it1\","
                + "\"order_id\":\"order_reg_it1\",\"short_url\":\"https://rzp.io/i/reg_it1\",\"status\":\"issued\"}");
        Response created = post(merchant, "/v1/mandates", UUID.randomUUID().toString(), Map.of("instrument", instrument,
                "max_amount", 500_000, "currency", "INR", "frequency", "monthly",
                "customer", Map.of("email", "asha@example.com", "phone", "+919999999999")));

        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        String mandateId = str(created.body(), "id");
        assertThat(str(created.body(), "provider")).isEqualTo(RazorpayApi.CODE);
        assertThat(str(created.body(), "next_action.url")).isEqualTo("https://rzp.io/i/reg_it1");
        JsonNode link = json.read(RAZORPAY.last("POST /v1/subscription_registration/auth_links").body(), JsonNode.class);
        assertThat(link.path("receipt").asString()).isEqualTo(mandateId);
        assertThat(link.path("amount").asLong()).isEqualTo(enach ? 0 : 100);
        String registrationId = str(created.body(), "registration_payment_id");
        if (enach) {
            assertThat(registrationId).as("eNACH registers without a charge").isNull();
        } else {
            assertThat(str(getPayment(merchant, registrationId), "latest_attempt.provider_reference")).isEqualTo("order_reg_it1");
        }

        String paid = "{\"entity\":\"event\",\"event\":\"invoice.paid\",\"payload\":{\"invoice\":{\"entity\":{\"id\":\"inv_it1\","
                + "\"receipt\":\"" + mandateId + "\",\"customer_id\":\"cust_it1\",\"order_id\":\"order_reg_it1\","
                + "\"payment_id\":\"pay_reg_it1\",\"status\":\"paid\"}},\"payment\":{\"entity\":{\"id\":\"pay_reg_it1\","
                + "\"order_id\":\"order_reg_it1\",\"token_id\":\"token_it1\",\"status\":\"captured\",\"amount\":"
                + (enach ? 0 : 100) + ",\"currency\":\"INR\"}}}}";
        assertThat(num(webhook(linked, "evt_inv_it1", paid, WEBHOOK_SECRET).body(), "received")).isEqualTo(enach ? 1 : 2);
        if (!enach) {
            assertThat(str(getPayment(merchant, registrationId), "status")).isEqualTo("succeeded");
        }
        Map<String, Object> authorized = get(merchant, "/v1/mandates/" + mandateId).body();
        assertThat(str(authorized, "status")).as("until the token is confirmed").isEqualTo("pending_authorization");
        assertThat(authorized).doesNotContainKey("next_action");
        webhook(linked, "evt_tok_it1", "{\"entity\":\"event\",\"event\":\"token.confirmed\",\"payload\":{\"token\":"
                + "{\"entity\":{\"id\":\"token_it1\",\"recurring_details\":{\"status\":\"confirmed\"}}}}}", WEBHOOK_SECRET);
        assertThat(str(get(merchant, "/v1/mandates/" + mandateId).body(), "status")).isEqualTo("active");

        RAZORPAY.on("POST /v1/orders", 200, enach ? "{\"id\":\"order_e_it1\"}"
                : "{\"id\":\"order_n_it1\",\"notification\":{\"status\":\"pending\"}}");
        String orderId = enach ? "order_e_it1" : "order_n_it1";
        Response debit = post(merchant, "/v1/mandates/" + mandateId + "/debits", UUID.randomUUID().toString(),
                Map.of("amount", 49_900, "merchant_debit_id", "inv_oct"));
        String debitId = str(debit.body(), "id");
        String paymentId = str(debit.body(), "payment_id");
        if (enach) {
            assertThat(str(debit.body(), "status")).as("eNACH debits without a notification").isEqualTo("ready");
        } else {
            mandateScheduler.processDueDebits();
            JsonNode order = json.read(RAZORPAY.last("POST /v1/orders").body(), JsonNode.class);
            assertThat(order.path("receipt").asString()).isEqualTo(debitId + ".1");
            assertThat(order.path("notification").path("token_id").asString()).isEqualTo("token_it1");
            long deliveredAt = clock.instant().getEpochSecond();
            webhook(linked, "evt_ntf_it1", "{\"entity\":\"event\",\"event\":\"order.notification.delivered\",\"payload\":"
                    + "{\"order\":{\"entity\":{\"id\":\"order_n_it1\",\"receipt\":\"" + debitId + ".1\",\"notification\":"
                    + "{\"status\":\"delivered\",\"delivered_at\":" + deliveredAt + "}}}}}", WEBHOOK_SECRET);
            assertThat(str(get(merchant, "/v1/mandates/" + mandateId + "/debits/" + debitId).body(), "status"))
                    .isEqualTo("ready");
            clock.advance(Duration.ofHours(25));
        }
        RAZORPAY.on("POST /v1/payments/create/recurring", 200, "{\"razorpay_payment_id\":\"pay_d_it1\"}");
        mandateScheduler.processDueDebits();

        JsonNode recurring = json.read(RAZORPAY.last("POST /v1/payments/create/recurring").body(), JsonNode.class);
        assertThat(recurring.path("order_id").asString()).isEqualTo(orderId);
        assertThat(recurring.path("token").asString()).isEqualTo("token_it1");
        assertThat(recurring.path("customer_id").asString()).isEqualTo("cust_it1");
        Map<String, Object> executing = getPayment(merchant, paymentId);
        assertThat(str(executing, "latest_attempt.provider_reference")).isEqualTo(orderId);
        if (enach) {
            assertThat(json.read(RAZORPAY.last("POST /v1/orders").body(), JsonNode.class).path("receipt").asString())
                    .as("an order keyed by the attempt").isEqualTo(str(executing, "latest_attempt.id"));
        }
        webhook(linked, "evt_pay_it1", "{\"entity\":\"event\",\"event\":\"payment.captured\",\"payload\":{\"payment\":"
                + "{\"entity\":{\"id\":\"pay_d_it1\",\"order_id\":\"" + orderId + "\",\"status\":\"captured\",\"amount\":49900,"
                + "\"currency\":\"INR\"}}}}", WEBHOOK_SECRET);
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
        assertThat(str(get(merchant, "/v1/mandates/" + mandateId + "/debits/" + debitId).body(), "status")).isEqualTo("succeeded");
    }

    // ------------------------------------------------------------------ settlement reconciliation (ADR-032)

    private record Paid(String paymentId, String attemptId) {
    }

    /** A card payment on a Razorpay link, captured through a signed webhook. */
    private Paid paidOnRazorpay(Linked linked, String suffix, long amount) {
        RAZORPAY.on("POST /v1/payment_links", 200, "{\"id\":\"plink_" + suffix + "\",\"short_url\":\"https://rzp.io/i/" + suffix
                + "\",\"status\":\"created\"}");
        String paymentId = str(createPayment(linked.merchant(), amount, "automatic"), "id");
        String attemptId = str(confirm(linked.merchant(), paymentId, card()).body(), "latest_attempt.id");
        String paid = "{\"entity\":\"event\",\"event\":\"payment_link.paid\",\"payload\":{\"payment_link\":{\"entity\":{"
                + "\"id\":\"plink_" + suffix + "\",\"reference_id\":\"" + attemptId + "\",\"order_id\":\"order_" + suffix
                + "\",\"status\":\"paid\"}},\"payment\":{\"entity\":{\"id\":\"pay_" + suffix + "\",\"order_id\":\"order_"
                + suffix + "\",\"status\":\"captured\",\"amount\":" + amount + ",\"currency\":\"INR\",\"method\":\"card\"}}}}";
        assertThat(webhook(linked, "evt_paid_" + suffix, paid, WEBHOOK_SECRET).status()).isEqualTo(200);
        assertThat(str(getPayment(linked.merchant(), paymentId), "status")).isEqualTo("succeeded");
        return new Paid(paymentId, attemptId);
    }

    private static String reconItem(String entityId, String type, long amount, long credit, long debit, String settlementId,
                                    Instant settledAt, String extra) {
        return "{\"entity_id\":\"" + entityId + "\",\"type\":\"" + type + "\",\"amount\":" + amount + ",\"credit\":" + credit
                + ",\"debit\":" + debit + ",\"currency\":\"INR\",\"settled\":true,\"created_at\":"
                + settledAt.minus(Duration.ofDays(1)).getEpochSecond() + ",\"settled_at\":" + settledAt.getEpochSecond()
                + ",\"settlement_id\":\"" + settlementId + "\"" + extra + "}";
    }

    /** Razorpay's recon for the India day of {@code settledAt} lists {@code items}, paid out as {@code payout}. */
    private void settles(Instant settledAt, String settlementId, long payout, String... items) {
        LocalDate day = LocalDate.ofInstant(settledAt, ZoneId.of("Asia/Kolkata"));
        RAZORPAY.on("GET /v1/settlements/recon/combined", 200, "{\"entity\":\"collection\",\"count\":0,\"items\":[]}")
                .on(String.format(Locale.ROOT, "GET /v1/settlements/recon/combined?year=%d&month=%02d&day=%02d&count=1000&skip=0",
                        day.getYear(), day.getMonthValue(), day.getDayOfMonth()), 200, "{\"entity\":\"collection\",\"count\":"
                        + items.length + ",\"items\":[" + String.join(",", items) + "]}")
                .on("GET /v1/settlements/" + settlementId, 200, "{\"id\":\"" + settlementId + "\",\"entity\":\"settlement\","
                        + "\"amount\":" + payout + ",\"status\":\"processed\",\"utr\":\"UTR_" + settlementId + "\"}");
    }

    private Response reconcileAround(TestMerchant merchant, Instant at) {
        return admin("POST", "/admin/v1/reconciliation/runs", Map.of("merchant_id", merchant.id(), "provider", RazorpayApi.CODE,
                "from", at.minus(Duration.ofHours(1)).toString(), "to", at.plus(Duration.ofHours(1)).toString()));
    }

    @Test
    void razorpaysSettlementReconIsReconciledAndAnUnexplainedAdjustmentIsLeftToFinance() {
        Linked linked = merchantOnRazorpay();
        Paid paid = paidOnRazorpay(linked, "r1", 49_900);
        RAZORPAY.on("GET /v1/payment_links/plink_r1", 200, "{\"id\":\"plink_r1\",\"order_id\":\"order_r1\",\"status\":\"paid\"}")
                .on("GET /v1/orders/order_r1/payments", 200, "{\"items\":[{\"id\":\"pay_r1\",\"status\":\"captured\","
                        + "\"amount\":49900,\"currency\":\"INR\"}]}")
                .on("POST /v1/payments/pay_r1/refund", 200, "{\"id\":\"rfnd_r1\",\"status\":\"processed\",\"amount\":10000,"
                        + "\"currency\":\"INR\"}");
        Response refund = post(linked.merchant(), "/v1/payments/" + paid.paymentId() + "/refunds", UUID.randomUUID().toString(),
                Map.of("amount", 10_000));
        assertThat(str(refund.body(), "status")).as(refund.raw()).isEqualTo("succeeded");
        Instant now = clock.instant();
        settles(now, "setl_r1", 48_722 - 10_590 - 5_000 + 250,
                reconItem("pay_r1", "payment", 49_900, 48_722, 0, "setl_r1", now,
                        ",\"order_id\":\"order_r1\",\"order_receipt\":\"" + paid.attemptId() + "\""),
                reconItem("rfnd_r1", "refund", 10_000, 0, 10_590, "setl_r1", now, ",\"payment_id\":\"pay_r1\",\"fee\":590"),
                reconItem("adj_r1", "adjustment", 5_000, 0, 5_000, "setl_r1", now, ",\"dispute_id\":\"disp_r1\""),
                reconItem("adj_r2", "adjustment", 250, 250, 0, "setl_r1", now, ",\"description\":\"Fee reversal\""));
        RAZORPAY.on("GET /v1/disputes/disp_r1", 200, "{\"id\":\"disp_r1\",\"payment_id\":\"pay_r1\"}")
                .on("GET /v1/payments/pay_r1", 200, "{\"id\":\"pay_r1\",\"order_id\":\"order_r1\",\"notes\":"
                        + "{\"pg_attempt_id\":\"" + paid.attemptId() + "\"}}");

        Response run = reconcileAround(linked.merchant(), now);

        assertThat(run.status()).as(run.raw()).isEqualTo(201);
        assertThat(str(run.body(), "status")).isEqualTo("completed");
        assertThat(num(run.body(), "lines_total")).isEqualTo(4);
        assertThat(num(run.body(), "lines_matched")).as("the capture and the refund").isEqualTo(2);
        assertThat(num(run.body(), "lines_auto_healed")).as("a chargeback only the report knew about").isEqualTo(1);
        assertThat(num(run.body(), "fee_amount")).as("the capture's fee and an instant refund's").isEqualTo(1_768);
        assertThat(num(run.body(), "chargeback_amount")).isEqualTo(5_000);
        assertThat(num(run.body(), "adjustment_amount")).isEqualTo(250);
        assertThat(num(run.body(), "settled_amount")).isEqualTo(33_382);
        Map<String, Object> exception = list(run.body(), "exceptions").getFirst();
        assertThat(list(run.body(), "exceptions")).as("the payout itself adds up").hasSize(1);
        assertThat(str(exception, "type")).isEqualTo("unmatched_adjustment");
        assertThat(str(exception, "reference")).isEqualTo("adj_r2");
        assertThat(num(exception, "actual_amount")).isEqualTo(250);
        assertThat(str(exception, "details")).endsWith("adjustment: Fee reversal");
        Map<String, Object> dispute = list(get(linked.merchant(), "/v1/payments/" + paid.paymentId() + "/disputes").body(), "data")
                .getFirst();
        assertThat(str(dispute, "reason")).isEqualTo("reported_in_settlement");
        assertThat(ledgerBalances(linked.merchant()))
                .as("the receivable keeps exactly the adjustment until finance books it")
                .containsEntry("psp_receivable", -250L)
                .containsEntry("psp_fees", 1_768L)
                .containsEntry("chargebacks", 5_000L)
                .containsEntry("bank_settlements", 33_382L);
    }

    @Test
    void aCaptureIsMissingAtRazorpayOnlyOnceTheSettlementLagHasPassed() {
        Linked linked = merchantOnRazorpay();
        Paid paid = paidOnRazorpay(linked, "l1", 20_000);
        Instant capturedAt = clock.instant();
        settles(capturedAt, "setl_none", 0);

        Response sameDay = reconcileAround(linked.merchant(), capturedAt);
        assertThat(num(sameDay.body(), "exceptions_opened")).as("Razorpay settles it later").isZero();

        clock.advance(Duration.ofDays(2));
        Response afterLag = reconcileAround(linked.merchant(), clock.instant());
        Map<String, Object> missing = list(afterLag.body(), "exceptions").getFirst();
        assertThat(str(missing, "type")).isEqualTo("missing_at_provider");
        assertThat(str(missing, "entity_id")).isEqualTo(paid.attemptId());

        clock.advance(Duration.ofDays(1));
        Instant late = clock.instant();
        settles(late, "setl_l1", 19_528, reconItem("pay_l1", "payment", 20_000, 19_528, 0, "setl_l1", late,
                ",\"order_id\":\"order_l1\",\"order_receipt\":\"" + paid.attemptId() + "\""));
        Response settledLate = reconcileAround(linked.merchant(), late);

        assertThat(num(settledLate.body(), "lines_matched")).isEqualTo(1);
        assertThat(num(settledLate.body(), "exceptions_opened")).isZero();
        assertThat(list(admin("GET", "/admin/v1/reconciliation/exceptions?status=resolved", null).body(), "data"))
                .extracting(e -> str(e, "resolution")).singleElement().asString().startsWith("auto-resolved");
        assertThat(ledgerBalances(linked.merchant())).containsEntry("psp_receivable", 0L);
    }
}
