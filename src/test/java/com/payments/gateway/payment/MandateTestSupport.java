package com.payments.gateway.payment;

import com.payments.gateway.payment.application.MandateScheduler;
import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.provider.spi.MandateRequests.MandateQuery;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.support.FakeMerchantEndpoint;
import com.payments.gateway.support.IntegrationTest;
import com.payments.gateway.support.OpenApiContract;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Fixtures for mandates on the mock PSP; the mandate worker is driven explicitly with the test clock. */
abstract class MandateTestSupport extends IntegrationTest {

    @Autowired
    protected MandateScheduler scheduler;

    protected static String key() {
        return UUID.randomUUID().toString();
    }

    protected static Map<String, Object> mandateRequest(String instrument, long maxAmount) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("instrument", instrument);
        request.put("max_amount", maxAmount);
        request.put("currency", "INR");
        request.put("frequency", "monthly");
        request.put("description", "Gym membership");
        request.put("customer", Map.of("reference", "cust_42", "name", "Asha Rao", "email", "asha@example.com",
                "phone", "+919999999999"));
        request.put("return_url", "https://merchant.example/subscriptions/42");
        return request;
    }

    protected Response postMandate(TestMerchant merchant, Map<String, Object> request, String idempotencyKey) {
        return post(merchant, "/v1/mandates", idempotencyKey, request);
    }

    protected Response createMandate(TestMerchant merchant, String instrument, long maxAmount) {
        Response created = postMandate(merchant, mandateRequest(instrument, maxAmount), key());
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        return created;
    }

    /** Registers a mandate and has the customer approve it at the mock PSP. */
    protected String activeMandate(TestMerchant merchant, String instrument, long maxAmount) {
        String mandateId = str(createMandate(merchant, instrument, maxAmount).body(), "id");
        Response approved = authorize(mandateId, "success");
        assertThat(approved.status()).as(approved.raw()).isEqualTo(200);
        assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("active");
        return mandateId;
    }

    protected Response authorize(String mandateId, String outcome) {
        return send("POST", "/simulator/" + providerOf(mandateId) + "/mandates/" + pspReference(mandateId) + "/complete",
                Map.of(), Map.of("outcome", outcome));
    }

    /** The customer pauses, resumes or revokes in their UPI or bank app. */
    protected Response customerAction(String mandateId, String status, boolean sendWebhook) {
        Response response = send("POST", "/simulator/" + providerOf(mandateId) + "/mandates/" + pspReference(mandateId)
                + "/status", Map.of(), Map.of("status", status, "send_webhook", sendWebhook));
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        return response;
    }

    protected Map<String, Object> getMandate(TestMerchant merchant, String mandateId) {
        Response response = get(merchant, "/v1/mandates/" + mandateId);
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        return response.body();
    }

    protected Response postDebit(TestMerchant merchant, String mandateId, Map<String, Object> request) {
        return post(merchant, "/v1/mandates/" + mandateId + "/debits", key(), request);
    }

    protected String createDebit(TestMerchant merchant, String mandateId, long amount, String merchantDebitId) {
        Response created = postDebit(merchant, mandateId, Map.of("amount", amount, "merchant_debit_id", merchantDebitId));
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        return str(created.body(), "id");
    }

    protected Map<String, Object> getDebit(TestMerchant merchant, String mandateId, String debitId) {
        Response response = get(merchant, "/v1/mandates/" + mandateId + "/debits/" + debitId);
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        return response.body();
    }

    protected String debitStatus(TestMerchant merchant, String mandateId, String debitId) {
        return str(getDebit(merchant, mandateId, debitId), "status");
    }

    /** Moves the clock to the debit's next action, if it is in the future, and runs the debit worker. */
    protected void runDebitWorkerWhenDue(String debitId) {
        Instant due = nextActionAt(debitId);
        if (due != null && due.isAfter(clock.instant())) {
            clock.set(due);
        }
        scheduler.processDueDebits();
    }

    /** Runs the worker until the debit's current cycle is executing; returns the attempt's PSP reference. */
    protected String executeCycle(TestMerchant merchant, String mandateId, String debitId) {
        for (int step = 0; step < 4 && !"executing".equals(debitStatus(merchant, mandateId, debitId)); step++) {
            runDebitWorkerWhenDue(debitId);
        }
        Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
        assertThat(str(debit, "status")).as(debit.toString()).isEqualTo("executing");
        return str(getPayment(merchant, str(debit, "payment_id")), "latest_attempt.provider_reference");
    }

    protected Instant nextActionAt(String debitId) {
        OffsetDateTime at = jdbc.sql("SELECT next_action_at FROM mandate_debits WHERE id = ?").param(1, debitId)
                .query(OffsetDateTime.class).single();
        return at == null ? null : at.toInstant();
    }

    protected String notificationReference(String debitId) {
        return jdbc.sql("SELECT notification_reference FROM mandate_debits WHERE id = ?").param(1, debitId)
                .query(String.class).single();
    }

    protected String pspReference(String mandateId) {
        return jdbc.sql("SELECT provider_reference FROM mandates WHERE id = ?").param(1, mandateId)
                .query(String.class).single();
    }

    protected String providerOf(String mandateId) {
        return jdbc.sql("SELECT provider_code FROM mandates WHERE id = ?").param(1, mandateId).query(String.class).single();
    }

    protected List<String> transitions(String mandateId, String entity) {
        return jdbc.sql("SELECT to_status FROM mandate_transitions WHERE mandate_id = ? AND entity = ? ORDER BY id")
                .param(1, mandateId).param(2, entity).query(String.class).list();
    }

    protected MockPaymentProvider mock(String code) {
        return mockProviders.stream().filter(provider -> provider.code().equals(code)).findFirst().orElseThrow();
    }

    /** Opens a provider's circuit with failed calls, then brings the PSP back; the circuit stays open for 30 s. */
    protected void openCircuit(TestMerchant merchant, String provider) {
        mock(provider).psp().setAvailable(false);
        for (int call = 0; call < 10; call++) {
            catchThrowable(() -> providerClient.fetchMandate(merchant.id(), provider,
                    new MandateQuery("mdt_unknown", MandateInstrument.ENACH, null, null, null)));
        }
        mock(provider).psp().setAvailable(true);
        assertThat(providerClient.isAvailable(provider)).isFalse();
    }

    /** Delivers pending merchant webhooks and returns their types, each checked against the Event schema. */
    protected List<String> deliveredEventTypes(FakeMerchantEndpoint endpoint) {
        deliveryWorker.deliverDue();
        return endpoint.received().stream()
                .peek(delivery -> OpenApiContract.get().assertSchema("Event", delivery.body()))
                .map(delivery -> str(json.read(delivery.body(), new TypeReference<Map<String, Object>>() { }), "type"))
                .toList();
    }

    protected String accountOf(TestMerchant merchant) {
        return jdbc.sql("SELECT id FROM merchant_provider_accounts WHERE merchant_id = ?").param(1, merchant.id())
                .query(String.class).single();
    }

    /** Posts a webhook as the mock PSP would to the merchant's account endpoint (platform secret). */
    protected Response pspWebhook(TestMerchant merchant, Map<String, Object> event) {
        String body = json.write(event);
        return send("POST", "/v1/webhooks/providers/" + ALPHA + "/" + accountOf(merchant),
                Map.of(MockPaymentProvider.SIGNATURE_HEADER, mock(ALPHA).sign(clock.instant().getEpochSecond(), body)), body);
    }
}
