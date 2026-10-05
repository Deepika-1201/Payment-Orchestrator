package com.payments.gateway.provider.cashfree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.payments.gateway.provider.spi.InboundWebhook;
import com.payments.gateway.provider.spi.InitiatePaymentRequest;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.ProviderCredentialsException;
import com.payments.gateway.provider.spi.ProviderDisputeResult;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderPaymentResult.Outcome;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.provider.spi.SettlementReport.LineType;
import com.payments.gateway.provider.spi.WebhookVerificationException;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import com.payments.gateway.support.StubPsp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Cashfree adapter against a local stub of Cashfree's PG API (ADR-031); shapes follow Cashfree's API reference. */
class CashfreePaymentProviderTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    static final MerchantAccount ACCOUNT = new MerchantAccount("mpa_1", "mer_1", "CASHFREE",
            Map.of("client_id", "cf_app_123", "client_secret", "cfsk_secret_abc"));
    private static final String ATTEMPT = "att_01M3H3TEST0000000000000000";

    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final StubPsp stub = cashfreeStub();

    /** Unknown ids get Cashfree's 404. */
    static StubPsp cashfreeStub() {
        return new StubPsp("/pg", 404, "{\"message\":\"something is not found\",\"code\":\"something_not_found\","
                + "\"type\":\"invalid_request_error\"}");
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private CashfreePaymentProvider provider(boolean upiS2s) {
        return provider(upiS2s, Duration.ofSeconds(2));
    }

    private CashfreePaymentProvider provider(boolean upiS2s, Duration readTimeout) {
        return new CashfreePaymentProvider(new CashfreeProperties(true, stub.baseUrl(), "2025-01-01", upiS2s,
                        Duration.ofMinutes(15), Duration.ofDays(5)),
                new CashfreeApi(stub.baseUrl(), Duration.ofSeconds(1), readTimeout, "2025-01-01", json), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static InitiatePaymentRequest request(PaymentMethod method, long paise, String phone) {
        return new InitiatePaymentRequest(ATTEMPT, "mer_1", Money.of(paise, "INR"), method, CaptureMethod.AUTOMATIC,
                "Order 42", "buyer@example.com", phone, "https://merchant.example/return", "203.0.113.7");
    }

    private JsonNode body(StubPsp.Recorded request) {
        return json.read(request.body(), JsonNode.class);
    }

    private static String link(String status) {
        return "{\"cf_link_id\":\"1996567\",\"link_id\":\"" + ATTEMPT + "\",\"link_status\":\"" + status + "\","
                + "\"link_currency\":\"INR\",\"link_amount\":499.01,\"link_amount_paid\":" + ("PAID".equals(status) ? "499.01" : "0")
                + ",\"link_url\":\"https://payments-test.cashfree.com/links/o1tf1nvcvjhg\"}";
    }

    @Test
    void cardsGoToACashfreeHostedLinkKeyedByTheAttemptWithExactRupeeAmounts() {
        stub.on("POST /pg/links", 200, link("ACTIVE"));

        var result = provider(false).initiatePayment(ACCOUNT, request(PaymentMethod.card(), 49_901, "+91 90000 90000"));

        assertThat(result.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(result.providerReference()).isEqualTo("cflink_1996567");
        assertThat(result.nextAction()).isEqualTo(NextAction.redirect("https://payments-test.cashfree.com/links/o1tf1nvcvjhg"));
        StubPsp.Recorded created = stub.last("POST /pg/links");
        assertThat(created.header("x-client-id")).isEqualTo("cf_app_123");
        assertThat(created.header("x-client-secret")).isEqualTo("cfsk_secret_abc");
        assertThat(created.header("x-api-version")).isEqualTo("2025-01-01");
        JsonNode link = body(created);
        assertThat(link.path("link_id").asString()).isEqualTo(ATTEMPT);
        assertThat(link.path("link_amount").decimalValue()).isEqualByComparingTo("499.01");
        assertThat(link.path("customer_details").path("customer_phone").asString()).as("normalized").isEqualTo("9000090000");
        assertThat(link.path("link_expiry_time").asString()).as("seconds always present, as in Cashfree's examples")
                .isEqualTo("2026-09-27T10:16:00Z");
        assertThat(link.path("link_meta").path("return_url").asString()).isEqualTo("https://merchant.example/return");
        assertThat(link.path("link_notify").path("send_sms").asBoolean(true)).isFalse();
    }

    @Test
    void aRetriedLinkIsReusedWhenCashfreeReportsTheLinkIdAsTaken() {
        stub.on("POST /pg/links", 409, "{\"message\":\"link_id already exists\",\"code\":\"link_already_exists\","
                        + "\"type\":\"invalid_request_error\"}")
                .on("GET /pg/links/" + ATTEMPT, 200, link("ACTIVE"));

        var result = provider(false).initiatePayment(ACCOUNT, request(PaymentMethod.card(), 49_901, "9000090000"));

        assertThat(result.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(result.providerReference()).isEqualTo("cflink_1996567");
    }

    @Test
    void phonesCashfreeWouldRejectFailFastAndRoutingSkipsPaymentsWithoutOne() {
        assertThat(CashfreePaymentProvider.indianMobile("+919000090000")).contains("9000090000");
        assertThat(CashfreePaymentProvider.indianMobile("09000090000")).contains("9000090000");
        assertThat(CashfreePaymentProvider.indianMobile("+14155550100")).isEmpty();
        assertThat(CashfreePaymentProvider.indianMobile("12345678")).isEmpty();

        var result = provider(false).initiatePayment(ACCOUNT, request(PaymentMethod.card(), 10_000, "+14155550100"));
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.failure().category()).isEqualTo(FailureCategory.VALIDATION);
        assertThat(stub.requests()).as("never sent").isEmpty();
        assertThat(provider(false).capabilities().supports(PaymentMethod.card(), Money.of(10_000, "INR"),
                CaptureMethod.AUTOMATIC, false)).isFalse();
        assertThat(provider(false).capabilities().supports(PaymentMethod.card(), Money.of(10_000, "INR"),
                CaptureMethod.AUTOMATIC, true)).isTrue();
    }

    @Test
    void upiIntentUsesCreateOrderAndOrderPayWhenServerToServerIsEnabled() {
        stub.on("POST /pg/orders", 200, "{\"cf_order_id\":\"2149460581\",\"order_id\":\"" + ATTEMPT + "\","
                        + "\"order_status\":\"ACTIVE\",\"payment_session_id\":\"session_abc\"}")
                .on("POST /pg/orders/sessions", 200, "{\"action\":\"custom\",\"cf_payment_id\":\"7845123001\","
                        + "\"payment_method\":\"upi\",\"channel\":\"link\",\"data\":{\"payload\":{\"default\":\"upi://pay?pa=cf@axis&am=499.01\"}}}");

        var result = provider(true).initiatePayment(ACCOUNT, request(PaymentMethod.upi(UpiFlow.INTENT, null), 49_901, "9000090000"));

        assertThat(result.providerReference()).isEqualTo(ATTEMPT);
        assertThat(result.nextAction()).isEqualTo(NextAction.upiIntent("upi://pay?pa=cf@axis&am=499.01"));
        JsonNode order = body(stub.last("POST /pg/orders"));
        assertThat(order.path("order_id").asString()).isEqualTo(ATTEMPT);
        assertThat(order.path("order_amount").decimalValue()).isEqualByComparingTo("499.01");
        assertThat(order.path("customer_details").path("customer_id").asString()).matches("c[0-9a-f]{32}");
        JsonNode pay = body(stub.last("POST /pg/orders/sessions"));
        assertThat(pay.path("payment_session_id").asString()).isEqualTo("session_abc");
        assertThat(pay.path("payment_method").path("upi").path("channel").asString()).isEqualTo("link");
    }

    @Test
    void aPaidLinkSucceedsWithTheAmountAndCardOfThePaymentOnCashfreesOrder() {
        stub.on("GET /pg/links/" + ATTEMPT, 200, link("PAID"))
                .on("GET /pg/links/" + ATTEMPT + "/orders", 200, "[{\"order_id\":\"CFPay_U1mgll3c0e9g_ehdcjjbtckf\",\"order_status\":\"PAID\"}]")
                .on("GET /pg/orders/CFPay_U1mgll3c0e9g_ehdcjjbtckf/payments", 200, "[{\"cf_payment_id\":\"12376124\","
                        + "\"payment_status\":\"SUCCESS\",\"payment_amount\":499.01,\"payment_currency\":\"INR\","
                        + "\"payment_method\":{\"card\":{\"card_number\":\"XXXXXXXXXXXX4738\",\"card_network\":\"visa\"}}}]");

        var result = provider(false).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, "cflink_1996567"));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(result.amount()).isEqualTo(Money.of(49_901, "INR"));
        assertThat(result.card()).isEqualTo(new CardDetails("visa", "4738"));
    }

    @Test
    void afterATimeoutTheAttemptIsFoundByItsIdAsLinkOrOrderOrReportedAsNeverSeen() {
        stub.on("GET /pg/links/" + ATTEMPT, 200, link("EXPIRED"))
                .on("GET /pg/links/" + ATTEMPT + "/orders", 200, "[]");
        var expired = provider(false).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, null));
        assertThat(expired.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(expired.providerReference()).isEqualTo("cflink_1996567");
        assertThat(expired.failure().category()).isEqualTo(FailureCategory.CUSTOMER);

        stub.reset();
        stub.on("GET /pg/orders/" + ATTEMPT, 200, "{\"order_id\":\"" + ATTEMPT + "\",\"order_status\":\"ACTIVE\"}")
                .on("GET /pg/orders/" + ATTEMPT + "/payments", 200, "[{\"payment_status\":\"FAILED\",\"error_details\":"
                        + "{\"error_reason\":\"auth_declined\",\"error_source\":\"customer\"}}]");
        assertThat(provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, null)).outcome())
                .as("the customer can still pay again on an active order").isEqualTo(Outcome.PENDING);

        stub.on("GET /pg/orders/" + ATTEMPT, 200, "{\"order_id\":\"" + ATTEMPT + "\",\"order_status\":\"EXPIRED\"}");
        var closed = provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, null));
        assertThat(closed.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(closed.failure().code()).isEqualTo("auth_declined");

        stub.reset();
        assertThat(provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, null)).outcome())
                .isEqualTo(Outcome.NOT_FOUND);
    }

    @Test
    void refundsGoToTheOrderThatWasPaidAndAreFoundByOurRefundId() {
        stub.on("GET /pg/links/" + ATTEMPT + "/orders", 200, "[{\"order_id\":\"CFPay_x1\",\"order_status\":\"PAID\"}]")
                .on("POST /pg/orders/CFPay_x1/refunds", 200, "{\"cf_refund_id\":\"1553338\",\"refund_id\":\"rfd_1\","
                        + "\"refund_amount\":100.00,\"refund_currency\":\"INR\",\"refund_status\":\"PENDING\"}");

        ProviderRefundResult created = provider(false).refund(ACCOUNT,
                new RefundRequest("rfd_1", ATTEMPT, "cflink_1996567", Money.of(10_000, "INR"), "damaged item"));

        assertThat(created.outcome()).isEqualTo(ProviderRefundResult.Outcome.PENDING);
        assertThat(created.providerReference()).isEqualTo("1553338");
        JsonNode refund = body(stub.last("POST /pg/orders/CFPay_x1/refunds"));
        assertThat(refund.path("refund_id").asString()).isEqualTo("rfd_1");
        assertThat(refund.path("refund_amount").decimalValue()).isEqualByComparingTo("100.00");

        stub.on("POST /pg/orders/CFPay_x1/refunds", 409, "{\"message\":\"refund_id already exists\",\"code\":\"refund_already_exists\"}")
                .on("GET /pg/orders/CFPay_x1/refunds/rfd_1", 200, "{\"cf_refund_id\":\"1553338\",\"refund_id\":\"rfd_1\","
                        + "\"refund_amount\":100.00,\"refund_currency\":\"INR\",\"refund_status\":\"SUCCESS\"}");
        assertThat(provider(false).refund(ACCOUNT, new RefundRequest("rfd_1", ATTEMPT, "cflink_1996567",
                Money.of(10_000, "INR"), null)).outcome()).isEqualTo(ProviderRefundResult.Outcome.SUCCEEDED);
        assertThat(provider(false).fetchRefundStatus(ACCOUNT, new RefundStatusQuery("rfd_1", null, "cflink_1996567", ATTEMPT))
                .outcome()).isEqualTo(ProviderRefundResult.Outcome.SUCCEEDED);
        assertThat(provider(false).fetchRefundStatus(ACCOUNT, new RefundStatusQuery("rfd_2", null, "cflink_1996567", ATTEMPT))
                .outcome()).isEqualTo(ProviderRefundResult.Outcome.NOT_FOUND);
    }

    @Test
    void failuresAreClassifiedSoOnlyDefinitelyUnprocessedCallsFailOver() {
        stub.on("POST /pg/links", 401, "{\"message\":\"authentication Failed\",\"code\":\"request_failed\",\"type\":\"authentication_error\"}");
        assertThatThrownBy(() -> provider(false).initiatePayment(ACCOUNT, request(PaymentMethod.card(), 10_000, "9000090000")))
                .isInstanceOf(ProviderCredentialsException.class);

        stub.on("POST /pg/links", 429, "{\"message\":\"Too many requests\",\"type\":\"rate_limit_error\"}");
        assertThatThrownBy(() -> provider(false).initiatePayment(ACCOUNT, request(PaymentMethod.card(), 10_000, "9000090000")))
                .isInstanceOf(ProviderUnavailableException.class);

        stub.on("POST /pg/links", 500, "{\"message\":\"internal Server Error\",\"type\":\"api_error\"}");
        assertThatThrownBy(() -> provider(false).initiatePayment(ACCOUNT, request(PaymentMethod.card(), 10_000, "9000090000")))
                .as("a 5xx after sending a write may have been processed").isInstanceOf(ProviderTimeoutException.class);

        stub.on("GET /pg/orders/" + ATTEMPT, 502, "{\"message\":\"bad gateway\"}");
        assertThatThrownBy(() -> provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, ATTEMPT)))
                .isInstanceOf(ProviderUnavailableException.class);

        stub.slow("POST /pg/links", 1_500);
        assertThatThrownBy(() -> provider(false, Duration.ofMillis(300))
                .initiatePayment(ACCOUNT, request(PaymentMethod.card(), 10_000, "9000090000")))
                .isInstanceOf(ProviderTimeoutException.class);
    }

    // ------------------------------------------------------------------ webhooks

    static InboundWebhook signed(String body, String secret, String timestamp) {
        return new InboundWebhook(Map.of("x-webhook-signature", CashfreePaymentProvider.signature(secret, timestamp, body),
                "x-webhook-timestamp", timestamp, "x-webhook-version", "2025-01-01"), body, NOW);
    }

    @Test
    void webhookSignaturesAreBase64HmacOfTimestampAndRawBodyWithTheClientSecret() {
        // Worked by hand from Cashfree's docs: Base64(HMAC-SHA256("1617695238078" + "{}", "secret")).
        assertThat(CashfreePaymentProvider.signature("secret", "1617695238078", "{}"))
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(hmac("secret", "1617695238078{}")));

        String body = "{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":{\"order_id\":\"" + ATTEMPT + "\"},"
                + "\"payment\":{\"cf_payment_id\":\"1\",\"payment_status\":\"SUCCESS\",\"payment_amount\":499.01,\"payment_currency\":\"INR\"}}}";
        CashfreePaymentProvider provider = provider(true);
        List<ProviderEvent> events = provider.parseWebhook(ACCOUNT, signed(body, "cfsk_secret_abc", "1790503200000"));
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventId()).startsWith("sha256:");
            assertThat(event.providerReference()).isEqualTo(ATTEMPT);
            assertThat(event.payment().outcome()).isEqualTo(Outcome.SUCCEEDED);
            assertThat(event.payment().amount()).isEqualTo(Money.of(49_901, "INR"));
        });

        assertThatThrownBy(() -> provider.parseWebhook(ACCOUNT, signed(body, "another_merchants_secret", "1790503200000")))
                .isInstanceOf(WebhookVerificationException.class);
        InboundWebhook good = signed(body, "cfsk_secret_abc", "1790503200000");
        assertThatThrownBy(() -> provider.parseWebhook(ACCOUNT, new InboundWebhook(good.headers(), body.replace("499.01", "1.00"), NOW)))
                .as("a tampered body").isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> provider.parseWebhook(ACCOUNT, new InboundWebhook(Map.of("x-webhook-signature",
                good.header("x-webhook-signature"), "x-webhook-timestamp", "1790503200001"), body, NOW)))
                .as("the timestamp is signed").isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> provider.parseWebhook(null, good)).as("the provider-wide endpoint")
                .isInstanceOf(WebhookVerificationException.class);
    }

    private static byte[] hmac(String secret, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void webhookEventsMapBackToOurAttemptsRefundsAndDisputes() {
        CashfreePaymentProvider provider = provider(false);
        String ts = "1790503200000";

        ProviderEvent linkPaid = provider.parseWebhook(ACCOUNT, signed("{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":"
                + "{\"order_id\":\"CFPay_g47u3888d0k0_tblfm766qc\",\"order_tags\":{\"cf_link_id\":\"1996567\"}},\"payment\":"
                + "{\"payment_status\":\"SUCCESS\",\"payment_amount\":499.01,\"payment_currency\":\"INR\",\"payment_method\":"
                + "{\"card\":{\"card_number\":\"XXXXXXXXXXXX4738\",\"card_network\":\"mastercard\"}}}}}", "cfsk_secret_abc", ts)).getFirst();
        assertThat(linkPaid.providerReference()).as("link payments arrive on Cashfree's own order").isEqualTo("cflink_1996567");
        assertThat(linkPaid.payment().card()).isEqualTo(new CardDetails("mastercard", "4738"));

        ProviderEvent failed = provider.parseWebhook(ACCOUNT, signed("{\"type\":\"PAYMENT_FAILED_WEBHOOK\",\"data\":{\"order\":"
                + "{\"order_id\":\"" + ATTEMPT + "\"},\"payment\":{\"payment_status\":\"FAILED\"},\"error_details\":"
                + "{\"error_reason\":\"invalid_amount\"}}}", "cfsk_secret_abc", ts)).getFirst();
        assertThat(failed.payment().outcome()).as("the customer can retry on the same order").isEqualTo(Outcome.PENDING);

        ProviderEvent refunded = provider.parseWebhook(ACCOUNT, signed("{\"type\":\"REFUND_STATUS_WEBHOOK\",\"data\":{\"refund\":"
                + "{\"cf_refund_id\":11325632,\"refund_id\":\"rfd_1\",\"order_id\":\"" + ATTEMPT + "\",\"refund_amount\":2.00,"
                + "\"refund_currency\":\"INR\",\"refund_status\":\"SUCCESS\"}}}", "cfsk_secret_abc", ts)).getFirst();
        assertThat(refunded.kind()).isEqualTo(ProviderEvent.Kind.REFUND);
        assertThat(refunded.merchantReference()).isEqualTo("rfd_1");
        assertThat(refunded.refund().amount()).isEqualTo(Money.of(200, "INR"));

        stub.on("GET /pg/orders/CFPay_g47u3888d0k0_tblfm766qc", 200, "{\"order_id\":\"CFPay_g47u3888d0k0_tblfm766qc\","
                + "\"order_tags\":{\"cf_link_id\":\"1996567\"}}");
        ProviderEvent chargeback = provider.parseWebhook(ACCOUNT, signed("{\"type\":\"DISPUTE_CLOSED\",\"data\":{\"dispute\":"
                + "{\"dispute_id\":\"433475257\",\"dispute_type\":\"CHARGEBACK\",\"reason_code\":\"4855\",\"reason_description\":"
                + "\"Goods or Services Not Provided\",\"dispute_amount\":45.00,\"respond_by\":\"2023-06-18T00:00:00+05:30\","
                + "\"dispute_status\":\"CHARGEBACK_MERCHANT_LOST\",\"dispute_amount_currency\":\"INR\"},\"order_details\":"
                + "{\"order_id\":\"CFPay_g47u3888d0k0_tblfm766qc\",\"cf_payment_id\":885457437}}}", "cfsk_secret_abc", ts)).getFirst();
        assertThat(chargeback.dispute().status()).isEqualTo(ProviderDisputeResult.Status.LOST);
        assertThat(chargeback.dispute().paymentReference()).isEqualTo("cflink_1996567");
        assertThat(chargeback.dispute().amount()).isEqualTo(Money.of(4_500, "INR"));
        assertThat(chargeback.dispute().respondBy()).isEqualTo(Instant.parse("2023-06-17T18:30:00Z"));

        assertThat(provider.parseWebhook(ACCOUNT, signed("{\"type\":\"DISPUTE_CREATED\",\"data\":{\"dispute\":{\"dispute_type\":"
                + "\"RETRIEVAL\",\"dispute_status\":\"RETRIEVAL_CREATED\"},\"order_details\":{\"order_id\":\"" + ATTEMPT + "\"}}}",
                "cfsk_secret_abc", ts))).as("retrieval requests move no money").isEmpty();
        assertThat(provider.parseWebhook(ACCOUNT, signed("{\"type\":\"PAYMENT_CHARGES_WEBHOOK\",\"data\":{}}", "cfsk_secret_abc", ts)))
                .isEmpty();
    }

    // ------------------------------------------------------------------ settlement reports (ADR-032)

    /** 4 October 2026 in India time. */
    private static final Instant DAY_START = Instant.parse("2026-10-03T18:30:00Z");
    private static final SettlementReportQuery DAY = new SettlementReportQuery("mer_1", DAY_START, DAY_START.plus(Duration.ofDays(1)));
    private static final String IN_WINDOW = "2026-10-04T12:00:00+05:30";
    private static final String S2S_ORDER = "{\"order_id\":\"" + ATTEMPT + "\",\"order_tags\":{\"pg_attempt_id\":\"" + ATTEMPT + "\"}}";
    private static final String LINK_ORDER = "{\"order_id\":\"CFPay_x1\",\"order_tags\":{\"cf_link_id\":\"1996567\"}}";

    private static String event(String type, String id, String saleType, String amount, String settled, String settlementDate,
                                String order, String extra) {
        return "{\"event_details\":{\"entity\":\"recon\",\"event_id\":\"" + id + "\",\"event_type\":\"" + type
                + "\",\"sale_type\":\"" + saleType + "\",\"event_status\":\"SUCCESS\",\"event_amount\":" + amount
                + ",\"event_settlement_amount\":" + settled + ",\"event_service_charge\":2.36,\"event_service_tax\":0.42,"
                + "\"event_currency\":\"INR\",\"event_time\":\"2026-10-02T15:06:41+05:30\",\"event_remarks\":null},"
                + "\"order_details\":" + order + ",\"settlement_details\":{\"cf_settlement_id\":\"5001\","
                + "\"settlement_date\":\"" + settlementDate + "\",\"utr\":\"CB5001\"}" + extra + "}";
    }

    private static String page(String cursor, String... events) {
        return "{\"cursor\":" + (cursor == null ? "null" : "\"" + cursor + "\"") + ",\"limit\":1000,\"data\":["
                + String.join(",", events) + "]}";
    }

    private void payout(String status, String amountSettled) {
        stub.on("POST /pg/settlements", 200, page(null, "{\"cf_settlement_id\":\"5001\",\"status\":\"" + status
                + "\",\"amount_settled\":" + amountSettled + ",\"settlement_utr\":\"CB5001\",\"payment_amount\":4000.00}"));
    }

    @Test
    void settlementReconciliationIsReadByCursorAndEventsAreKeptBySettlementDate() {
        stub.onSequence("POST /pg/settlement/recon",
                page("c2",
                        event("PAYMENT", "9001", "CREDIT", "499.00", "487.22", IN_WINDOW, LINK_ORDER, ""),
                        event("PAYMENT", "9002", "CREDIT", "200.00", "196.00", IN_WINDOW, S2S_ORDER, ""),
                        event("REFUND", "9003", "DEBIT", "100.00", "105.90", IN_WINDOW, LINK_ORDER,
                                ",\"refund_details\":{\"refund_id\":\"rfd_1\",\"refund_arn\":null}"),
                        event("PAYMENT", "9010", "CREDIT", "10.00", "9.80", "2026-10-05T10:00:00+05:30", S2S_ORDER, "")),
                page(null,
                        event("DISPUTE", "9004", "DEBIT", "45.00", "45.00", IN_WINDOW, S2S_ORDER, ""),
                        event("CHARGEBACK_REVERSAL", "9005", "CREDIT", "30.00", "30.00", IN_WINDOW,
                                "{\"order_id\":\"order_amb\"}", ""),
                        event("OTHER_ADJUSTMENT", "9006", "CREDIT", "12.50", "12.50", IN_WINDOW, "{\"order_id\":null}", "")
                                .replace("\"event_remarks\":null", "\"event_remarks\":\"Fee waiver\""),
                        event("RISK", "9007", "DEBIT", "75.00", "null", IN_WINDOW, "{}", ""),
                        event("REFUND", "9008", "DEBIT", "5.00", "5.00", IN_WINDOW, S2S_ORDER, "")
                                .replace("\"event_status\":\"SUCCESS\"", "\"event_status\":\"PENDING\"")))
                .on("GET /pg/orders/" + ATTEMPT + "/disputes", 200, "[{\"dispute_id\":433475257,\"dispute_amount\":45.00,"
                        + "\"dispute_type\":\"CHARGEBACK\"},{\"cf_dispute_id\":422427,\"dispute_amount\":10.00}]")
                .on("GET /pg/orders/order_amb/disputes", 200, "[{\"dispute_id\":\"d1\",\"dispute_amount\":30.00},"
                        + "{\"dispute_id\":\"d2\",\"dispute_amount\":30}]");
        payout("SUCCESS", "3952.80");

        SettlementReport report = provider(false).fetchSettlementReport(ACCOUNT, DAY);

        assertThat(report.lines()).extracting(SettlementReport.Line::lineId, SettlementReport.Line::type,
                        SettlementReport.Line::providerReference, SettlementReport.Line::merchantReference,
                        line -> line.amount().amount(), line -> line.fee().amount())
                .containsExactly(
                        tuple("PAYMENT:9001", LineType.PAYMENT, "cflink_1996567", null, 49_900L, 1_178L),
                        tuple("PAYMENT:9002", LineType.PAYMENT, ATTEMPT, ATTEMPT, 20_000L, 400L),
                        tuple("REFUND:9003", LineType.REFUND, null, "rfd_1", 10_000L, 590L),
                        tuple("DISPUTE:9004", LineType.CHARGEBACK, "433475257", ATTEMPT, 4_500L, 0L),
                        tuple("CHARGEBACK_REVERSAL:9005", LineType.CHARGEBACK_REVERSAL, "cfevent_CHARGEBACK_REVERSAL:9005",
                                null, 3_000L, 0L),
                        tuple("OTHER_ADJUSTMENT:9006", LineType.ADJUSTMENT_CREDIT, null, null, 1_250L, 0L),
                        tuple("RISK:9007", LineType.ADJUSTMENT_DEBIT, null, null, 7_500L, 0L));
        assertThat(report.lines()).filteredOn(line -> line.type().name().startsWith("ADJUSTMENT"))
                .extracting(SettlementReport.Line::description).containsExactly("OTHER_ADJUSTMENT: Fee waiver", "RISK");
        assertThat(report.lines().getFirst().occurredAt()).isEqualTo(Instant.parse("2026-10-02T09:36:41Z"));
        assertThat(report.settlements()).containsExactly(
                new SettlementReport.Settlement("5001", 395_280, "INR", "CB5001", Instant.parse("2026-10-04T06:30:00Z")));

        List<StubPsp.Recorded> pages = stub.requests().stream().filter(r -> r.path().equals("/pg/settlement/recon")).toList();
        assertThat(pages).hasSize(2);
        JsonNode first = body(pages.getFirst());
        assertThat(first.path("pagination").path("limit").asInt()).isEqualTo(1000);
        assertThat(first.path("pagination").path("cursor").isNull()).as("Cashfree: pass a null cursor first").isTrue();
        assertThat(first.path("filters").path("start_date").asString()).as("a day of margin").isEqualTo("2026-10-02T18:30:00Z");
        assertThat(first.path("filters").path("end_date").asString()).isEqualTo("2026-10-05T18:30:00Z");
        assertThat(body(pages.getLast()).path("pagination").path("cursor").asString()).isEqualTo("c2");
        assertThat(pages.getFirst().header("x-api-version")).isEqualTo("2025-01-01");
        assertThat(pages.getFirst().header("x-client-id")).isEqualTo("cf_app_123");
        assertThat(body(stub.last("POST /pg/settlements")).path("filters").path("cf_settlement_ids").get(0).asString())
                .isEqualTo("5001");
    }

    @Test
    void aFailedPayoutIsReportedAsNothingPaidAndPendingOrUnknownOnesFailTheReport() {
        stub.on("POST /pg/settlement/recon", 200, page(null, event("PAYMENT", "9001", "CREDIT", "499.00", "487.22", IN_WINDOW,
                S2S_ORDER, "")));
        payout("FAILED", "487.22");
        assertThat(provider(false).fetchSettlementReport(ACCOUNT, DAY).settlements())
                .extracting(SettlementReport.Settlement::netAmount).containsExactly(0L);

        payout("PENDING_WITH_BANK", "487.22");
        assertThatThrownBy(() -> provider(false).fetchSettlementReport(ACCOUNT, DAY))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("5001 has status 'PENDING_WITH_BANK'").hasMessageContaining("reconcile this window again");

        stub.on("POST /pg/settlements", 200, page(null));
        assertThatThrownBy(() -> provider(false).fetchSettlementReport(ACCOUNT, DAY))
                .isInstanceOf(ProviderUnavailableException.class).hasMessageContaining("did not return settlement 5001");
    }

    @Test
    void amountsMustBeWholePaiseAndCursorsMustMoveOn() {
        stub.on("POST /pg/settlement/recon", 200, page(null, event("PAYMENT", "9001", "CREDIT", "499.005", "487.22",
                IN_WINDOW, S2S_ORDER, "")));
        payout("SUCCESS", "487.22");
        assertThatThrownBy(() -> provider(false).fetchSettlementReport(ACCOUNT, DAY))
                .as("never rounded").isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("PAYMENT:9001 without an exact event_amount");

        stub.on("POST /pg/settlement/recon", 200, page("same", event("PAYMENT", "9001", "CREDIT", "499.00", "487.22",
                IN_WINDOW, S2S_ORDER, "")));
        assertThatThrownBy(() -> provider(false).fetchSettlementReport(ACCOUNT, DAY))
                .isInstanceOf(ProviderUnavailableException.class).hasMessageContaining("repeated a pagination cursor");
    }

    @Test
    void refusedReportRequestsCarryCashfreesReasonAndBadKeysAreCredentialFailures() {
        stub.on("POST /pg/settlement/recon", 400, "{\"message\":\"start_date is invalid\",\"code\":\"request_failed\","
                + "\"type\":\"invalid_request_error\"}");
        assertThatThrownBy(() -> provider(false).fetchSettlementReport(ACCOUNT, DAY))
                .isInstanceOf(ProviderUnavailableException.class).hasMessageContaining("start_date is invalid");

        stub.on("POST /pg/settlement/recon", 401, "{\"message\":\"authentication Failed\",\"type\":\"authentication_error\"}");
        assertThatThrownBy(() -> provider(false).fetchSettlementReport(ACCOUNT, DAY))
                .isInstanceOf(ProviderCredentialsException.class);

        stub.on("POST /pg/settlement/recon", 503, "{}");
        assertThatThrownBy(() -> provider(false).fetchSettlementReport(ACCOUNT, DAY))
                .as("a report request only reads, so a 5xx means it can simply be retried")
                .isExactlyInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void cashfreeDeclaresSettlementReportsAndItsSettlementLag() {
        assertThat(provider(false).capabilities().settlementReports()).isTrue();
        assertThat(provider(false).settlementLag()).isEqualTo(Duration.ofDays(5));
    }
}
