package com.payments.gateway.provider.razorpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.payments.gateway.provider.spi.InboundWebhook;
import com.payments.gateway.provider.spi.MandateRequests.CreateMandateRequest;
import com.payments.gateway.provider.spi.MandateRequests.DebitNotificationQuery;
import com.payments.gateway.provider.spi.MandateRequests.DebitNotificationRequest;
import com.payments.gateway.provider.spi.MandateRequests.ExecuteDebitRequest;
import com.payments.gateway.provider.spi.MandateRequests.MandateQuery;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderMandateResult;
import com.payments.gateway.provider.spi.ProviderNotificationResult;
import com.payments.gateway.provider.spi.ProviderPaymentResult.Outcome;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.MandateFrequency;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
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

/** Razorpay recurring payments (ADR-035, LLD §18.9) against a stub of Razorpay's API. */
class RazorpayMandateTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final MerchantAccount ACCOUNT = new MerchantAccount("mpa_1", "mer_1", "RAZORPAY",
            Map.of("key_id", "rzp_test_abc", "key_secret", "secret123", "webhook_secret", "whsec_rzp"));
    private static final String MANDATE = "mdt_01M4TEST000000000000000000";
    private static final Instant END = NOW.plus(Duration.ofDays(365));
    private static final Instant AUTHORIZE_BY = NOW.plus(Duration.ofHours(24));

    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final StubPsp stub = RazorpayPaymentProviderTest.razorpayStub();

    @AfterEach
    void stop() {
        stub.close();
    }

    private RazorpayPaymentProvider provider(boolean mandates) {
        return new RazorpayPaymentProvider(new RazorpayProperties(true, stub.baseUrl(), false, Duration.ofMinutes(15),
                Duration.ofDays(5), mandates), new RazorpayApi(stub.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2),
                "rzp_test_", json), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CreateMandateRequest registration(MandateInstrument instrument, Money registrationAmount) {
        return new CreateMandateRequest(MANDATE, "mer_1", instrument, Money.of(500_000, "INR"), MandateFrequency.MONTHLY,
                NOW, END, AUTHORIZE_BY, "Gym membership", "Asha Rao", "asha@example.com", "+919999999999",
                registrationAmount == null ? null : "att_reg_1", registrationAmount, "https://merchant.example/return");
    }

    private JsonNode body(StubPsp.Recorded request) {
        return json.read(request.body(), JsonNode.class);
    }

    private static String invoice(String status, String extra) {
        return "{\"id\":\"inv_1\",\"entity\":\"invoice\",\"receipt\":\"" + MANDATE + "\",\"customer_id\":\"cust_1\","
                + "\"order_id\":\"order_reg_1\",\"short_url\":\"https://rzp.io/i/reg1\",\"status\":\"" + status + "\"" + extra + "}";
    }

    private static String token(String status) {
        return "{\"id\":\"token_1\",\"entity\":\"token\",\"method\":\"upi\",\"recurring\":true,"
                + "\"recurring_details\":{\"status\":\"" + status + "\",\"failure_reason\":null}}";
    }

    private static InboundWebhook signed(String eventId, String body) {
        return new InboundWebhook(Map.of("X-Razorpay-Signature", Hashing.hmacSha256Hex("whsec_rzp", body),
                "X-Razorpay-Event-Id", eventId), body, NOW);
    }

    @Test
    void mandatesAreOfferedOnlyOnceRazorpayHasEnabledRecurringPayments() {
        assertThat(provider(false).capabilities().supportsMandate(MandateInstrument.UPI_AUTOPAY, Money.of(500_000, "INR")))
                .isFalse();
        assertThat(provider(true).capabilities().mandates().get(MandateInstrument.ENACH).registrationAmount()).isZero();
        assertThat(provider(true).capabilities().supportsMandate(MandateInstrument.CARD, Money.of(10_000_001, "INR")))
                .isFalse();
    }

    @Test
    void aUpiMandateRegistersThroughALinkKeyedByTheMandateId() {
        stub.on("POST /v1/subscription_registration/auth_links", 200, invoice("issued", ""));

        ProviderMandateResult result = provider(true).createMandate(ACCOUNT,
                registration(MandateInstrument.UPI_AUTOPAY, Money.of(100, "INR")));

        assertThat(result.status()).isEqualTo(ProviderMandateResult.Status.PENDING);
        assertThat(result.providerReference()).isEqualTo("inv_1");
        assertThat(result.providerCustomerReference()).isEqualTo("cust_1");
        assertThat(result.registrationPaymentReference()).isEqualTo("order_reg_1");
        assertThat(result.nextAction()).isEqualTo(NextAction.redirect("https://rzp.io/i/reg1"));
        JsonNode sent = body(stub.last("POST /v1/subscription_registration/auth_links"));
        assertThat(sent.path("receipt").asString()).isEqualTo(MANDATE);
        assertThat(sent.path("type").asString()).isEqualTo("link");
        assertThat(sent.path("amount").asLong()).isEqualTo(100);
        assertThat(sent.path("expire_by").asLong()).isEqualTo(AUTHORIZE_BY.getEpochSecond());
        assertThat(sent.path("customer").path("contact").asString()).isEqualTo("+919999999999");
        JsonNode registration = sent.path("subscription_registration");
        assertThat(registration.path("method").asString()).isEqualTo("upi");
        assertThat(registration.path("max_amount").asLong()).isEqualTo(500_000);
        assertThat(registration.path("expire_at").asLong()).isEqualTo(END.getEpochSecond());
        assertThat(registration.path("frequency").asString()).isEqualTo("monthly");
    }

    @Test
    void anEnachRegistrationIsFreeAndHasNoFrequency() {
        stub.on("POST /v1/subscription_registration/auth_links", 200, invoice("issued", ""));

        provider(true).createMandate(ACCOUNT, registration(MandateInstrument.ENACH, null));

        JsonNode sent = body(stub.last("POST /v1/subscription_registration/auth_links"));
        assertThat(sent.path("amount").asLong()).isZero();
        assertThat(sent.path("subscription_registration").path("method").asString()).isEqualTo("emandate");
        assertThat(sent.path("subscription_registration").has("frequency")).isFalse();
    }

    @Test
    void aRepeatedRegistrationIsFoundByItsReceipt() {
        stub.on("POST /v1/subscription_registration/auth_links", 400,
                        "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Duplicate request: receipt already used\"}}")
                .on("GET /v1/invoices", 200, "{\"items\":[" + invoice("issued", "") + "]}");

        ProviderMandateResult result = provider(true).createMandate(ACCOUNT,
                registration(MandateInstrument.UPI_AUTOPAY, Money.of(100, "INR")));

        assertThat(result.providerReference()).isEqualTo("inv_1");
        assertThat(stub.last("GET /v1/invoices").query()).isEqualTo("receipt=" + MANDATE);
    }

    @Test
    void aPaidRegistrationIsActiveOnceItsTokenIsConfirmed() {
        stub.on("GET /v1/invoices/inv_1", 200, invoice("paid", ",\"payment_id\":\"pay_reg_1\""))
                .on("GET /v1/payments/pay_reg_1", 200, "{\"id\":\"pay_reg_1\",\"token_id\":\"token_1\"}")
                .onSequence("GET /v1/customers/cust_1/tokens/token_1", token("initiated"), token("confirmed"));
        MandateQuery query = new MandateQuery(MANDATE, MandateInstrument.ENACH, "inv_1", null, null);

        ProviderMandateResult awaitingBank = provider(true).fetchMandate(ACCOUNT, query);
        ProviderMandateResult confirmed = provider(true).fetchMandate(ACCOUNT, query);

        assertThat(awaitingBank.status()).isEqualTo(ProviderMandateResult.Status.PENDING);
        assertThat(awaitingBank.nextAction()).as("the customer has done their part").isNull();
        assertThat(confirmed.status()).isEqualTo(ProviderMandateResult.Status.ACTIVE);
        assertThat(confirmed.providerMandateReference()).isEqualTo("token_1");
        assertThat(confirmed.providerCustomerReference()).isEqualTo("cust_1");
    }

    @Test
    void registrationAndTokenStatusesMapToMandateStatuses() {
        stub.on("GET /v1/invoices/inv_issued", 200, invoice("issued", ""))
                .on("GET /v1/invoices/inv_expired", 200, invoice("expired", ""))
                .on("GET /v1/invoices/inv_cancelled", 200, invoice("cancelled", ""))
                .on("GET /v1/customers/cust_1/tokens/token_paused", 200, token("paused"))
                .on("GET /v1/customers/cust_1/tokens/token_rejected", 200, token("rejected"))
                .on("GET /v1/customers/cust_1/tokens/token_cancelled", 200, token("cancelled"));
        RazorpayPaymentProvider provider = provider(true);

        assertThat(provider.fetchMandate(ACCOUNT, byInvoice("inv_issued")).nextAction()).isNotNull();
        assertThat(provider.fetchMandate(ACCOUNT, byInvoice("inv_expired")).failure().code()).isEqualTo("registration_expired");
        assertThat(provider.fetchMandate(ACCOUNT, byInvoice("inv_cancelled")).status()).isEqualTo(ProviderMandateResult.Status.REVOKED);
        assertThat(provider.fetchMandate(ACCOUNT, byToken("token_paused")).status()).isEqualTo(ProviderMandateResult.Status.PAUSED);
        assertThat(provider.fetchMandate(ACCOUNT, byToken("token_rejected")).failure().code()).isEqualTo("mandate_rejected");
        assertThat(provider.fetchMandate(ACCOUNT, byToken("token_cancelled")).status()).isEqualTo(ProviderMandateResult.Status.REVOKED);
        assertThat(provider.fetchMandate(ACCOUNT, byInvoice("inv_unknown")).status()).isEqualTo(ProviderMandateResult.Status.NOT_FOUND);
    }

    private static MandateQuery byInvoice(String invoiceId) {
        return new MandateQuery(MANDATE, MandateInstrument.UPI_AUTOPAY, invoiceId, null, null);
    }

    private static MandateQuery byToken(String tokenId) {
        return new MandateQuery(MANDATE, MandateInstrument.UPI_AUTOPAY, "inv_1", tokenId, "cust_1");
    }

    @Test
    void revocationCancelsTheUnpaidLinkOrTheToken() {
        stub.on("GET /v1/invoices/inv_1", 200, invoice("issued", ""))
                .on("POST /v1/invoices/inv_1/cancel", 200, invoice("cancelled", ""))
                .on("PUT /v1/customers/cust_1/tokens/token_upi/cancel", 200, token("cancelled"))
                .on("DELETE /v1/customers/cust_1/tokens/token_card", 200, "{\"deleted\":true}");
        RazorpayPaymentProvider provider = provider(true);

        ProviderMandateResult unpaid = provider.revokeMandate(ACCOUNT, byInvoice("inv_1"));
        ProviderMandateResult upi = provider.revokeMandate(ACCOUNT,
                new MandateQuery(MANDATE, MandateInstrument.UPI_AUTOPAY, "inv_1", "token_upi", "cust_1"));
        ProviderMandateResult card = provider.revokeMandate(ACCOUNT,
                new MandateQuery(MANDATE, MandateInstrument.CARD, "inv_1", "token_card", "cust_1"));

        assertThat(unpaid.status()).isEqualTo(ProviderMandateResult.Status.REVOKED);
        assertThat(upi.status()).isEqualTo(ProviderMandateResult.Status.REVOKED);
        assertThat(card.status()).isEqualTo(ProviderMandateResult.Status.REVOKED);
        assertThat(stub.requests()).extracting(r -> r.method() + " " + r.path()).contains(
                "POST /v1/invoices/inv_1/cancel", "PUT /v1/customers/cust_1/tokens/token_upi/cancel",
                "DELETE /v1/customers/cust_1/tokens/token_card");
    }

    @Test
    void aTokenAlreadyCancelledCountsAsRevoked() {
        stub.on("PUT /v1/customers/cust_1/tokens/token_1/cancel", 400,
                        "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Token is already cancelled\"}}")
                .on("GET /v1/customers/cust_1/tokens/token_1", 200, token("cancelled"));

        ProviderMandateResult result = provider(true).revokeMandate(ACCOUNT, byToken("token_1"));

        assertThat(result.status()).isEqualTo(ProviderMandateResult.Status.REVOKED);
    }

    @Test
    void eachCycleIsNotifiedOnAnOrderKeyedByTheNotificationId() {
        Instant debitAfter = NOW.plus(Duration.ofHours(25));
        stub.on("POST /v1/orders", 200, "{\"id\":\"order_n1\",\"receipt\":\"mdd_1.2\",\"notification\":{\"status\":\"pending\"}}")
                .on("GET /v1/orders/order_n1", 200, "{\"id\":\"order_n1\",\"notification\":{\"status\":\"delivered\","
                        + "\"delivered_at\":" + NOW.plusSeconds(90).getEpochSecond() + "}}");
        RazorpayPaymentProvider provider = provider(true);

        ProviderNotificationResult requested = provider.notifyDebit(ACCOUNT, new DebitNotificationRequest("mdd_1.2", "mdd_1",
                MANDATE, MandateInstrument.UPI_AUTOPAY, "token_1", "cust_1", Money.of(49_900, "INR"), debitAfter, "October"));
        ProviderNotificationResult delivered = provider.fetchDebitNotification(ACCOUNT,
                new DebitNotificationQuery("mdd_1.2", "order_n1"));

        assertThat(requested.status()).isEqualTo(ProviderNotificationResult.Status.PENDING);
        assertThat(requested.providerReference()).isEqualTo("order_n1");
        JsonNode order = body(stub.last("POST /v1/orders"));
        assertThat(order.path("receipt").asString()).isEqualTo("mdd_1.2");
        assertThat(order.path("notification").path("token_id").asString()).isEqualTo("token_1");
        assertThat(order.path("notification").path("payment_after").asLong()).isEqualTo(debitAfter.getEpochSecond());
        assertThat(delivered.status()).isEqualTo(ProviderNotificationResult.Status.DELIVERED);
        assertThat(delivered.deliveredAt()).isEqualTo(NOW.plusSeconds(90));
    }

    @Test
    void aNotificationRazorpayCouldNotDeliverFails() {
        stub.on("GET /v1/orders/order_n1", 200, "{\"id\":\"order_n1\",\"notification\":{\"status\":\"failed\"}}");

        ProviderNotificationResult result = provider(true).fetchDebitNotification(ACCOUNT,
                new DebitNotificationQuery("mdd_1.1", "order_n1"));

        assertThat(result.status()).isEqualTo(ProviderNotificationResult.Status.FAILED);
        assertThat(result.failure().code()).isEqualTo("notification_failed");
    }

    @Test
    void aNotifiedDebitRunsOnTheCyclesOrderWithTheToken() {
        stub.on("POST /v1/payments/create/recurring", 200, "{\"razorpay_payment_id\":\"pay_d1\",\"razorpay_order_id\":\"order_n1\"}");

        var result = provider(true).executeDebit(ACCOUNT, new ExecuteDebitRequest("att_d1", "mdd_1", MANDATE,
                MandateInstrument.UPI_AUTOPAY, "token_1", "cust_1", "order_n1", Money.of(49_900, "INR"), "October",
                "asha@example.com", "+919999999999"));

        assertThat(result.outcome()).isEqualTo(Outcome.PENDING);
        assertThat(result.providerReference()).isEqualTo("order_n1");
        JsonNode sent = body(stub.last("POST /v1/payments/create/recurring"));
        assertThat(sent.path("order_id").asString()).isEqualTo("order_n1");
        assertThat(sent.path("token").asString()).isEqualTo("token_1");
        assertThat(sent.path("customer_id").asString()).isEqualTo("cust_1");
        assertThat(sent.path("recurring").asString()).isEqualTo("1");
        assertThat(sent.path("notes").path("pg_attempt_id").asString()).isEqualTo("att_d1");
        assertThat(stub.requests()).extracting(r -> r.method() + " " + r.path()).doesNotContain("POST /v1/orders");
    }

    @Test
    void anEnachDebitRunsOnAnOrderKeyedByTheAttempt() {
        stub.on("POST /v1/orders", 200, "{\"id\":\"order_e1\",\"receipt\":\"att_e1\"}")
                .on("POST /v1/payments/create/recurring", 400,
                        "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The token is not active\",\"reason\":\"token_inactive\"}}");

        var result = provider(true).executeDebit(ACCOUNT, new ExecuteDebitRequest("att_e1", "mdd_2", MANDATE,
                MandateInstrument.ENACH, "token_2", "cust_1", null, Money.of(2_500_000, "INR"), null, "asha@example.com",
                "+919999999999"));

        assertThat(body(stub.last("POST /v1/orders")).path("receipt").asString()).isEqualTo("att_e1");
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.providerReference()).isEqualTo("order_e1");
        assertThat(result.failure().code()).isEqualTo("token_inactive");
    }

    @Test
    void mandateWebhooksMapToTheMandateTheChargeAndTheNotification() {
        RazorpayPaymentProvider provider = provider(true);

        List<ProviderEvent> paid = provider.parseWebhook(ACCOUNT, signed("evt_paid", "{\"event\":\"invoice.paid\",\"payload\":{"
                + "\"invoice\":{\"entity\":" + invoice("paid", ",\"payment_id\":\"pay_reg_1\"") + "},"
                + "\"payment\":{\"entity\":{\"id\":\"pay_reg_1\",\"order_id\":\"order_reg_1\",\"token_id\":\"token_1\","
                + "\"status\":\"captured\",\"amount\":100,\"currency\":\"INR\"}}}}"));
        assertThat(paid).extracting(ProviderEvent::eventId).containsExactly("evt_paid#payment", "evt_paid#mandate");
        assertThat(paid.getFirst().payment().outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(paid.getFirst().providerReference()).isEqualTo("order_reg_1");
        ProviderEvent registered = paid.getLast();
        assertThat(registered.merchantReference()).isEqualTo(MANDATE);
        assertThat(registered.mandate().status()).isEqualTo(ProviderMandateResult.Status.PENDING);
        assertThat(registered.mandate().providerMandateReference()).isEqualTo("token_1");

        ProviderEvent confirmed = provider.parseWebhook(ACCOUNT, signed("evt_tok", "{\"event\":\"token.confirmed\","
                + "\"payload\":{\"token\":{\"entity\":" + token("confirmed") + "}}}")).getFirst();
        assertThat(confirmed.kind()).isEqualTo(ProviderEvent.Kind.MANDATE);
        assertThat(confirmed.mandate().status()).isEqualTo(ProviderMandateResult.Status.ACTIVE);
        assertThat(confirmed.mandate().providerMandateReference()).isEqualTo("token_1");

        ProviderEvent expired = provider.parseWebhook(ACCOUNT, signed("evt_exp", "{\"event\":\"invoice.expired\","
                + "\"payload\":{\"invoice\":{\"entity\":" + invoice("expired", "") + "}}}")).getFirst();
        assertThat(expired.mandate().failure().code()).isEqualTo("registration_expired");

        ProviderEvent delivered = provider.parseWebhook(ACCOUNT, signed("evt_ntf", "{\"event\":\"order.notification.delivered\","
                + "\"created_at\":" + NOW.minusSeconds(30).getEpochSecond() + ",\"payload\":{\"order\":{\"entity\":{\"id\":\"order_n1\","
                + "\"receipt\":\"mdd_1.1\",\"notification\":{\"status\":\"delivered\"}}}}}")).getFirst();
        assertThat(delivered.kind()).isEqualTo(ProviderEvent.Kind.NOTIFICATION);
        assertThat(delivered.merchantReference()).isEqualTo("mdd_1.1");
        assertThat(delivered.notification().deliveredAt()).as("the event's time when Razorpay omits it")
                .isEqualTo(NOW.minusSeconds(30));
    }

    @Test
    void aDebitsSettlementLineCarriesTheAttemptFromThePaymentNotesNotTheNotificationReceipt() {
        Instant settledAt = Instant.parse("2026-10-04T06:30:00Z");
        String item = "{\"entity_id\":\"pay_d1\",\"type\":\"payment\",\"amount\":49900,\"credit\":48722,\"debit\":0,"
                + "\"currency\":\"INR\",\"settled\":true,\"created_at\":" + settledAt.minus(Duration.ofDays(2)).getEpochSecond()
                + ",\"settled_at\":" + settledAt.getEpochSecond() + ",\"settlement_id\":\"setl_d1\",\"order_id\":\"order_n1\","
                + "\"order_receipt\":\"mdd_1.1\",\"notes\":{\"pg_attempt_id\":\"att_d1\"}}";
        stub.on("GET /v1/settlements/recon/combined", 200, "{\"entity\":\"collection\",\"count\":0,\"items\":[]}")
                .on("GET /v1/settlements/recon/combined?year=2026&month=10&day=04&count=1000&skip=0", 200,
                        "{\"entity\":\"collection\",\"count\":1,\"items\":[" + item + "]}")
                .on("GET /v1/settlements/setl_d1", 200, "{\"id\":\"setl_d1\",\"amount\":48722,\"status\":\"processed\","
                        + "\"utr\":\"UTR1\"}");
        Instant dayStart = Instant.parse("2026-10-03T18:30:00Z");

        SettlementReport report = provider(true).fetchSettlementReport(ACCOUNT,
                new SettlementReportQuery("mer_1", dayStart, dayStart.plus(Duration.ofDays(1))));

        assertThat(report.lines()).singleElement().satisfies(line -> {
            assertThat(line.merchantReference()).isEqualTo("att_d1");
            assertThat(line.providerReference()).isEqualTo("order_n1");
        });
    }
}
