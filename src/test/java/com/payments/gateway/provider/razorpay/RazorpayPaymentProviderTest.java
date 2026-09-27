package com.payments.gateway.provider.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payments.gateway.provider.spi.InboundWebhook;
import com.payments.gateway.provider.spi.InitiatePaymentRequest;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.ProviderCredentialsException;
import com.payments.gateway.provider.spi.ProviderDisputeResult;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderPaymentResult.Outcome;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.provider.spi.WebhookVerificationException;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import com.payments.gateway.support.StubPsp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Razorpay adapter against a local stub of Razorpay's REST API (ADR-030); request shapes follow Razorpay's docs. */
class RazorpayPaymentProviderTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final MerchantAccount ACCOUNT = new MerchantAccount("mpa_1", "mer_1", "RAZORPAY",
            Map.of("key_id", "rzp_test_abc", "key_secret", "secret123", "webhook_secret", "whsec_rzp"));
    private static final String ATTEMPT = "att_01M3H3TEST0000000000000000";

    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final StubPsp stub = razorpayStub();

    /** Unknown ids get Razorpay's 400 "does not exist". */
    static StubPsp razorpayStub() {
        return new StubPsp("/v1", 400,
                "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The id provided does not exist\"}}");
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private RazorpayPaymentProvider provider(boolean upiS2s) {
        return provider(upiS2s, Duration.ofSeconds(2));
    }

    private RazorpayPaymentProvider provider(boolean upiS2s, Duration readTimeout) {
        RazorpayProperties properties = new RazorpayProperties(true, stub.baseUrl(), upiS2s, Duration.ofMinutes(15));
        return new RazorpayPaymentProvider(properties,
                new RazorpayApi(stub.baseUrl(), Duration.ofSeconds(1), readTimeout, "rzp_test_", json), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static InitiatePaymentRequest request(PaymentMethod method) {
        return new InitiatePaymentRequest(ATTEMPT, "mer_1", Money.of(49_900, "INR"), method, CaptureMethod.AUTOMATIC,
                "Order 42", "buyer@example.com", "+919000090000", "https://merchant.example/return", "203.0.113.7");
    }

    private JsonNode body(StubPsp.Recorded request) {
        return json.read(request.body(), JsonNode.class);
    }

    @Test
    void webhookSignaturesMatchRazorpaysOwnSdkTestVector() {
        assertThat(Hashing.hmacSha256Hex("123456", "{\"a\":1,\"b\":2,\"c\":{\"d\":3}}"))
                .isEqualTo("2fe04e22977002e6c7cb553adab8b460cb9e2a4970d5953cb27a8472752e3bbc");
    }

    @Test
    void upiIntentCreatesAnOrderKeyedByTheAttemptThenAServerToServerUpiPayment() {
        stub.on("POST /v1/orders", 200, "{\"id\":\"order_1\",\"amount\":49900,\"currency\":\"INR\",\"receipt\":\"" + ATTEMPT + "\",\"status\":\"created\"}")
                .on("POST /v1/payments/create/upi", 200, "{\"razorpay_payment_id\":\"pay_1\",\"link\":\"upi://pay?pa=merchant@razorpay&am=499.00\"}");

        var result = provider(true).initiatePayment(ACCOUNT, request(PaymentMethod.upi(UpiFlow.INTENT, null)));

        assertThat(result.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(result.providerReference()).isEqualTo("order_1");
        assertThat(result.nextAction()).isEqualTo(NextAction.upiIntent("upi://pay?pa=merchant@razorpay&am=499.00"));
        JsonNode order = body(stub.last("POST /v1/orders"));
        assertThat(order.path("receipt").asString()).isEqualTo(ATTEMPT);
        assertThat(order.path("amount").asLong()).isEqualTo(49_900);
        assertThat(order.path("notes").path("pg_attempt_id").asString()).isEqualTo(ATTEMPT);
        JsonNode upi = body(stub.last("POST /v1/payments/create/upi"));
        assertThat(upi.path("order_id").asString()).isEqualTo("order_1");
        assertThat(upi.path("upi").path("flow").asString()).isEqualTo("intent");
        assertThat(upi.path("contact").asString()).isEqualTo("+919000090000");
        assertThat(stub.last("POST /v1/orders").authorization())
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("rzp_test_abc:secret123".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aRetriedAttemptReusesTheOrderRazorpayAlreadyHas() {
        stub.on("POST /v1/orders", 400, "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Duplicate request. This request has already been processed.\"}}")
                .on("GET /v1/orders", 200, "{\"items\":[{\"id\":\"order_1\",\"receipt\":\"" + ATTEMPT + "\",\"created_at\":1790000000}]}")
                .on("POST /v1/payments/create/upi", 200, "{\"razorpay_payment_id\":\"pay_1\",\"link\":\"upi://pay?pa=m@rzp\"}");

        var result = provider(true).initiatePayment(ACCOUNT, request(PaymentMethod.upi(UpiFlow.QR, null)));

        assertThat(result.providerReference()).isEqualTo("order_1");
        assertThat(result.nextAction().type()).isEqualTo(NextAction.Type.DISPLAY_QR);
        assertThat(stub.last("GET /v1/orders").query()).isEqualTo("receipt=" + ATTEMPT);
    }

    @Test
    void aRetriedLinkIsReusedWhenRazorpaySaysTheReferenceWasAlreadyAttempted() {
        stub.on("POST /v1/payment_links", 400, "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\","
                        + "\"description\":\"payment link creation with reference ID already attempted\"}}")
                .on("GET /v1/payment_links", 200, "{\"payment_links\":[{\"id\":\"plink_1\",\"reference_id\":\"" + ATTEMPT
                        + "0\",\"status\":\"created\"},{\"id\":\"plink_2\",\"reference_id\":\"" + ATTEMPT + "\",\"status\":\"created\"}]}");

        var result = provider(true).initiatePayment(ACCOUNT, request(PaymentMethod.card()));

        assertThat(result.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(result.providerReference()).as("exact reference match, not a prefix").isEqualTo("plink_2");
    }

    @Test
    void cardsGoToARazorpayHostedPaymentLinkThatExpiresWithThePaymentWindow() {
        stub.on("POST /v1/payment_links", 200, "{\"id\":\"plink_1\",\"short_url\":\"https://rzp.io/i/abc\",\"status\":\"created\"}");

        var result = provider(true).initiatePayment(ACCOUNT, request(PaymentMethod.card()));

        assertThat(result.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(result.providerReference()).isEqualTo("plink_1");
        assertThat(result.nextAction()).isEqualTo(NextAction.redirect("https://rzp.io/i/abc"));
        JsonNode link = body(stub.last("POST /v1/payment_links"));
        assertThat(link.path("reference_id").asString()).isEqualTo(ATTEMPT);
        assertThat(link.path("callback_url").asString()).isEqualTo("https://merchant.example/return");
        assertThat(link.path("callback_method").asString()).isEqualTo("get");
        assertThat(link.path("expire_by").asLong()).isEqualTo(NOW.plus(Duration.ofMinutes(16)).getEpochSecond());
        assertThat(link.path("notify").path("sms").asBoolean(true)).isFalse();
        assertThat(link.has("customer")).as("not prefilled by the hosted page; some accounts then require a name").isFalse();
    }

    @Test
    void withoutServerToServerUpiUpiAlsoUsesTheHostedPage() {
        stub.on("POST /v1/payment_links", 200, "{\"id\":\"plink_2\",\"short_url\":\"https://rzp.io/i/upi\",\"status\":\"created\"}");

        var result = provider(false).initiatePayment(ACCOUNT, request(PaymentMethod.upi(UpiFlow.INTENT, null)));

        assertThat(result.nextAction().type()).isEqualTo(NextAction.Type.REDIRECT);
        assertThat(provider(false).capabilities().methods().get(com.payments.gateway.shared.model.MethodType.UPI).upiFlows())
                .containsExactly(UpiFlow.INTENT);
        assertThat(stub.requests()).noneMatch(r -> r.path().equals("/v1/orders"));
    }

    @Test
    void aCapturedOrderPaymentSucceedsWithTheReportedAmountAndCard() {
        stub.on("GET /v1/orders/order_1", 200, "{\"id\":\"order_1\",\"created_at\":" + NOW.getEpochSecond() + "}")
                .on("GET /v1/orders/order_1/payments", 200, "{\"items\":[{\"id\":\"pay_1\",\"status\":\"captured\",\"amount\":49900,"
                        + "\"currency\":\"INR\",\"card\":{\"network\":\"MasterCard\",\"last4\":\"4242\"}}]}");

        var result = provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, "order_1"));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(result.amount()).isEqualTo(Money.of(49_900, "INR"));
        assertThat(result.card()).isEqualTo(new CardDetails("mastercard", "4242"));
    }

    @Test
    void aFailedPaymentIsFinalOnlyAfterThePaymentWindowBecauseRazorpayMayStillCaptureIt() {
        stub.on("GET /v1/orders/order_1/payments", 200, "{\"items\":[{\"id\":\"pay_1\",\"status\":\"failed\",\"amount\":49900,"
                + "\"currency\":\"INR\",\"error_source\":\"issuer\",\"error_reason\":\"payment_failed\",\"error_description\":\"Declined\"}]}");

        stub.on("GET /v1/orders/order_1", 200, "{\"id\":\"order_1\",\"created_at\":" + NOW.minusSeconds(60).getEpochSecond() + "}");
        assertThat(provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, "order_1")).outcome())
                .isEqualTo(Outcome.PENDING);

        stub.on("GET /v1/orders/order_1", 200, "{\"id\":\"order_1\",\"created_at\":" + NOW.minusSeconds(3600).getEpochSecond() + "}");
        var late = provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, "order_1"));
        assertThat(late.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(late.failure().category()).isEqualTo(FailureCategory.ISSUER);
        assertThat(late.failure().code()).isEqualTo("payment_failed");
    }

    @Test
    void afterATimeoutTheAttemptIsFoundByLinkReferenceOrOrderReceiptOrIsReportedAsNeverSeen() {
        // Razorpay gives a link's order the link's reference_id as receipt; the link's own status must win.
        stub.on("GET /v1/orders", 200, "{\"items\":[{\"id\":\"order_link\",\"receipt\":\"" + ATTEMPT + "\",\"created_at\":"
                        + NOW.getEpochSecond() + "}]}")
                .on("GET /v1/orders/order_link/payments", 200, "{\"items\":[]}")
                .on("GET /v1/payment_links", 200, "{\"payment_links\":[{\"id\":\"plink_1\",\"reference_id\":\"" + ATTEMPT
                        + "\",\"order_id\":\"order_link\",\"status\":\"expired\"}]}");
        var expired = provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, null));
        assertThat(expired.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(expired.providerReference()).isEqualTo("plink_1");
        assertThat(expired.failure().category()).isEqualTo(FailureCategory.CUSTOMER);
        assertThat(stub.last("GET /v1/payment_links").query()).isEqualTo("reference_id=" + ATTEMPT);

        stub.on("GET /v1/payment_links", 200, "{\"payment_links\":[]}")
                .on("GET /v1/orders", 200, "{\"items\":[{\"id\":\"order_s2s\",\"receipt\":\"" + ATTEMPT + "\",\"created_at\":"
                        + NOW.getEpochSecond() + "}]}")
                .on("GET /v1/orders/order_s2s/payments", 200, "{\"items\":[{\"id\":\"pay_1\",\"status\":\"captured\","
                        + "\"amount\":49900,\"currency\":\"INR\"}]}");
        var s2s = provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, null));
        assertThat(s2s.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(s2s.providerReference()).isEqualTo("order_s2s");

        stub.on("GET /v1/orders", 200, "{\"items\":[]}");
        assertThat(provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, null)).outcome())
                .isEqualTo(Outcome.NOT_FOUND);
    }

    @Test
    void anAuthorizedPaymentIsCapturedForTheAuthorizedAmount() {
        stub.on("GET /v1/orders/order_1/payments", 200, "{\"items\":[{\"id\":\"pay_1\",\"status\":\"authorized\",\"amount\":49900,\"currency\":\"INR\"}]}")
                .on("POST /v1/payments/pay_1/capture", 200, "{\"id\":\"pay_1\",\"status\":\"captured\",\"amount\":49900,\"currency\":\"INR\"}");

        var result = provider(true).capture(ACCOUNT, new CaptureRequest(ATTEMPT, "order_1", Money.of(49_900, "INR")));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
        JsonNode capture = body(stub.last("POST /v1/payments/pay_1/capture"));
        assertThat(capture.path("amount").asLong()).isEqualTo(49_900);
        assertThat(capture.path("currency").asString()).isEqualTo("INR");
    }

    @Test
    void refundsAreKeyedByOurRefundIdAndFoundAgainAfterATimeout() {
        stub.on("GET /v1/payment_links/plink_1", 200, "{\"id\":\"plink_1\",\"order_id\":\"order_9\"}")
                .on("GET /v1/orders/order_9/payments", 200, "{\"items\":[{\"id\":\"pay_9\",\"status\":\"captured\",\"amount\":49900,\"currency\":\"INR\"}]}")
                .on("POST /v1/payments/pay_9/refund", 200, "{\"id\":\"rfnd_1\",\"status\":\"pending\",\"amount\":10000,\"currency\":\"INR\",\"receipt\":\"rfd_1\"}");

        ProviderRefundResult created = provider(true).refund(ACCOUNT,
                new RefundRequest("rfd_1", ATTEMPT, "plink_1", Money.of(10_000, "INR"), "damaged"));
        assertThat(created.outcome()).isEqualTo(ProviderRefundResult.Outcome.PENDING);
        assertThat(created.providerReference()).isEqualTo("rfnd_1");
        JsonNode refund = body(stub.last("POST /v1/payments/pay_9/refund"));
        assertThat(refund.path("receipt").asString()).isEqualTo("rfd_1");
        assertThat(refund.path("amount").asLong()).isEqualTo(10_000);

        stub.on("GET /v1/payments/pay_9/refunds", 200, "{\"items\":[{\"id\":\"rfnd_other\",\"receipt\":\"rfd_0\",\"status\":\"processed\"},"
                + "{\"id\":\"rfnd_1\",\"receipt\":\"rfd_1\",\"status\":\"processed\",\"amount\":10000,\"currency\":\"INR\"}]}");
        ProviderRefundResult found = provider(true).fetchRefundStatus(ACCOUNT, new RefundStatusQuery("rfd_1", null, "plink_1", ATTEMPT));
        assertThat(found.outcome()).isEqualTo(ProviderRefundResult.Outcome.SUCCEEDED);
        assertThat(found.providerReference()).isEqualTo("rfnd_1");
        assertThat(provider(true).fetchRefundStatus(ACCOUNT, new RefundStatusQuery("rfd_2", null, "plink_1", ATTEMPT)).outcome())
                .isEqualTo(ProviderRefundResult.Outcome.NOT_FOUND);
    }

    @Test
    void failuresAreClassifiedSoOnlyDefinitelyUnprocessedCallsFailOver() {
        stub.on("GET /v1/orders/order_1", 401, "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The api key provided is invalid\"}}");
        assertThatThrownBy(() -> provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, "order_1")))
                .isInstanceOf(ProviderCredentialsException.class);

        stub.on("POST /v1/orders", 400, "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Authentication failed\"}}");
        assertThatThrownBy(() -> provider(true).initiatePayment(ACCOUNT, request(PaymentMethod.upi(UpiFlow.INTENT, null))))
                .as("Razorpay also reports bad keys as 400").isInstanceOf(ProviderCredentialsException.class);

        int calls = stub.requests().size();
        MerchantAccount liveKey = new MerchantAccount("mpa_2", "mer_1", "RAZORPAY",
                Map.of("key_id", "rzp_live_abc", "key_secret", "secret123", "webhook_secret", "whsec_rzp"));
        assertThatThrownBy(() -> provider(true).initiatePayment(liveKey, request(PaymentMethod.card())))
                .as("a live key on a sandbox deployment").isInstanceOf(ProviderCredentialsException.class)
                .hasMessageContaining("rzp_test_");
        assertThat(stub.requests()).as("refused before calling Razorpay").hasSize(calls);

        stub.on("POST /v1/orders", 429, "{}");
        assertThatThrownBy(() -> provider(true).initiatePayment(ACCOUNT, request(PaymentMethod.upi(UpiFlow.INTENT, null))))
                .isInstanceOf(ProviderUnavailableException.class);

        stub.on("POST /v1/orders", 503, "{}");
        assertThatThrownBy(() -> provider(true).initiatePayment(ACCOUNT, request(PaymentMethod.upi(UpiFlow.INTENT, null))))
                .as("a 5xx after sending a write may have been processed").isInstanceOf(ProviderTimeoutException.class);

        stub.on("GET /v1/orders/order_1", 503, "{}");
        assertThatThrownBy(() -> provider(true).fetchPaymentStatus(ACCOUNT, new PaymentStatusQuery(ATTEMPT, "order_1")))
                .isInstanceOf(ProviderUnavailableException.class);

        stub.slow("POST /v1/orders", 1_500);
        assertThatThrownBy(() -> provider(true, Duration.ofMillis(300))
                .initiatePayment(ACCOUNT, request(PaymentMethod.upi(UpiFlow.INTENT, null))))
                .isInstanceOf(ProviderTimeoutException.class);

        RazorpayPaymentProvider unreachable = new RazorpayPaymentProvider(
                new RazorpayProperties(true, java.net.URI.create("http://127.0.0.1:1/v1"), true, Duration.ofMinutes(15)),
                new RazorpayApi(java.net.URI.create("http://127.0.0.1:1/v1"), Duration.ofSeconds(1), Duration.ofSeconds(1), "rzp_test_", json),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> unreachable.initiatePayment(ACCOUNT, request(PaymentMethod.upi(UpiFlow.INTENT, null))))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    // ------------------------------------------------------------------ webhooks

    private InboundWebhook signed(String body, String secret) {
        return new InboundWebhook(Map.of("X-Razorpay-Signature", Hashing.hmacSha256Hex(secret, body),
                "X-Razorpay-Event-Id", "evt_1"), body, NOW);
    }

    @Test
    void webhooksAreAcceptedOnlyWithTheAccountsSignatureAndAnEventId() {
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\"order_1\","
                + "\"status\":\"captured\",\"amount\":49900,\"currency\":\"INR\",\"notes\":{\"pg_attempt_id\":\"" + ATTEMPT + "\"}}}}}";
        RazorpayPaymentProvider provider = provider(true);

        List<ProviderEvent> events = provider.parseWebhook(ACCOUNT, signed(body, "whsec_rzp"));
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo("evt_1");
            assertThat(event.providerReference()).isEqualTo("order_1");
            assertThat(event.merchantReference()).isEqualTo(ATTEMPT);
            assertThat(event.payment().outcome()).isEqualTo(Outcome.SUCCEEDED);
        });

        assertThatThrownBy(() -> provider.parseWebhook(ACCOUNT, signed(body, "someone_elses_secret")))
                .isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> provider.parseWebhook(ACCOUNT, new InboundWebhook(Map.of(
                "X-Razorpay-Signature", Hashing.hmacSha256Hex("whsec_rzp", body)), body.replace("49900", "1"), NOW)))
                .as("a tampered body").isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> provider.parseWebhook(ACCOUNT, new InboundWebhook(Map.of(
                "X-Razorpay-Signature", Hashing.hmacSha256Hex("whsec_rzp", body)), body, NOW)))
                .as("no event id").isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> provider.parseWebhook(null, signed(body, "whsec_rzp")))
                .as("the provider-wide endpoint").isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void webhookEventsMapBackToOurAttemptsRefundsAndDisputes() {
        RazorpayPaymentProvider provider = provider(true);

        ProviderEvent linkPaid = provider.parseWebhook(ACCOUNT, signed("{\"event\":\"payment_link.paid\",\"payload\":{"
                + "\"payment_link\":{\"entity\":{\"id\":\"plink_1\",\"reference_id\":\"" + ATTEMPT + "\",\"status\":\"paid\"}},"
                + "\"payment\":{\"entity\":{\"id\":\"pay_1\",\"status\":\"captured\",\"amount\":49900,\"currency\":\"INR\","
                + "\"card\":{\"network\":\"RuPay\",\"last4\":\"0153\"}}}}}", "whsec_rzp")).getFirst();
        assertThat(linkPaid.providerReference()).isEqualTo("plink_1");
        assertThat(linkPaid.merchantReference()).isEqualTo(ATTEMPT);
        assertThat(linkPaid.payment().card()).isEqualTo(new CardDetails("rupay", "0153"));

        ProviderEvent failed = provider.parseWebhook(ACCOUNT, signed("{\"event\":\"payment.failed\",\"payload\":{\"payment\":"
                + "{\"entity\":{\"id\":\"pay_2\",\"order_id\":\"order_1\",\"status\":\"failed\",\"notes\":{\"pg_attempt_id\":\"" + ATTEMPT
                + "\"}}}}}", "whsec_rzp")).getFirst();
        assertThat(failed.payment().outcome()).as("Razorpay can still capture a failed UPI payment").isEqualTo(Outcome.PENDING);

        ProviderEvent refunded = provider.parseWebhook(ACCOUNT, signed("{\"event\":\"refund.processed\",\"payload\":{\"refund\":"
                + "{\"entity\":{\"id\":\"rfnd_1\",\"receipt\":\"rfd_1\",\"status\":\"processed\",\"amount\":10000,\"currency\":\"INR\"}}}}",
                "whsec_rzp")).getFirst();
        assertThat(refunded.kind()).isEqualTo(ProviderEvent.Kind.REFUND);
        assertThat(refunded.merchantReference()).isEqualTo("rfd_1");
        assertThat(refunded.refund().outcome()).isEqualTo(ProviderRefundResult.Outcome.SUCCEEDED);

        ProviderEvent disputeLost = provider.parseWebhook(ACCOUNT, signed("{\"event\":\"payment.dispute.lost\",\"payload\":{"
                + "\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\"order_1\",\"notes\":{\"pg_attempt_id\":\"" + ATTEMPT + "\"}}},"
                + "\"dispute\":{\"entity\":{\"id\":\"disp_1\",\"payment_id\":\"pay_1\",\"amount\":39000,\"currency\":\"INR\","
                + "\"amount_deducted\":39000,\"reason_code\":\"fraud\",\"respond_by\":1790431400,\"status\":\"lost\"}}}}", "whsec_rzp")).getFirst();
        assertThat(disputeLost.kind()).isEqualTo(ProviderEvent.Kind.DISPUTE);
        assertThat(disputeLost.merchantReference()).isEqualTo(ATTEMPT);
        assertThat(disputeLost.dispute().status()).isEqualTo(ProviderDisputeResult.Status.LOST);
        assertThat(disputeLost.dispute().amount()).isEqualTo(Money.of(39_000, "INR"));
        assertThat(disputeLost.dispute().respondBy()).isEqualTo(Instant.ofEpochSecond(1_790_431_400L));

        assertThat(provider.parseWebhook(ACCOUNT, signed("{\"event\":\"payment.downtime.started\",\"payload\":{}}", "whsec_rzp")))
                .as("events the gateway does not use").isEmpty();
    }
}
