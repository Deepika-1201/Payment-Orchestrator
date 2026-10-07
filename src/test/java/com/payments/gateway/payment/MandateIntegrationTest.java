package com.payments.gateway.payment;

import com.payments.gateway.provider.mock.MockPsp.MandateState;
import com.payments.gateway.support.FakeMerchantEndpoint;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** Mandate registration, lifecycle and revocation on the mock PSP (requirements §8.1, ADR-035, LLD §18). */
class MandateIntegrationTest extends MandateTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {"upi_autopay", "card"})
    void upiAndCardMandatesAuthorizeWithARupeeChargeAndDebitAfterTheNotice(String instrument) {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            Map<String, Object> request = mandateRequest(instrument, 500_000);

            Response created = assertContract("POST", "/v1/mandates", request, postMandate(merchant, request, key()));

            assertThat(created.status()).isEqualTo(201);
            String mandateId = str(created.body(), "id");
            assertThat(str(created.body(), "status")).isEqualTo("pending_authorization");
            assertThat(str(created.body(), "instrument")).isEqualTo(instrument);
            assertThat(str(created.body(), "provider")).isEqualTo(ALPHA);
            assertThat(str(created.body(), "next_action.type")).isEqualTo("redirect");
            assertThat(str(created.body(), "next_action.url"))
                    .endsWith("/simulator/" + ALPHA + "/mandates/" + pspReference(mandateId));
            String registrationId = str(created.body(), "registration_payment_id");
            Map<String, Object> registration = getPayment(merchant, registrationId);
            assertThat(num(registration, "amount")).isEqualTo(100);
            assertThat(str(registration, "status")).isEqualTo("requires_action");
            assertThat(str(registration, "mandate_id")).isEqualTo(mandateId);
            assertThat(str(registration, "latest_attempt.method")).isEqualTo("mandate");

            authorize(mandateId, "success");

            Map<String, Object> active = assertContract("GET", "/v1/mandates/{mandate_id}", null,
                    get(merchant, "/v1/mandates/" + mandateId)).body();
            assertThat(str(active, "status")).isEqualTo("active");
            assertThat(active).containsKey("activated_at").doesNotContainKey("next_action");
            assertThat(str(getPayment(merchant, registrationId), "status")).isEqualTo("succeeded");

            Instant createdAt = clock.instant();
            Map<String, Object> debitRequest = Map.of("amount", 99_900, "merchant_debit_id", "inv_2026_10",
                    "description", "October");
            Response debitCreated = assertContract("POST", "/v1/mandates/{mandate_id}/debits", debitRequest,
                    post(merchant, "/v1/mandates/" + mandateId + "/debits", key(), debitRequest));
            assertThat(debitCreated.status()).isEqualTo(201);
            String debitId = str(debitCreated.body(), "id");
            String paymentId = str(debitCreated.body(), "payment_id");
            assertThat(str(debitCreated.body(), "status")).isEqualTo("scheduled");
            Instant due = Instant.parse(str(debitCreated.body(), "due_at"));
            assertThat(due).as("after the 24 h notice, with an hour to deliver it").isEqualTo(createdAt.plus(Duration.ofHours(25)));
            Map<String, Object> payment = getPayment(merchant, paymentId);
            assertThat(str(payment, "status")).isEqualTo("requires_payment_method");
            assertThat(str(payment, "merchant_order_id")).isEqualTo("inv_2026_10");
            assertThat(str(payment, "mandate_id")).isEqualTo(mandateId);
            assertThat(Instant.parse(str(payment, "expires_at")))
                    .as("open through 4 cycles of retry interval, notice and notification timeout, plus 3 days")
                    .isEqualTo(due.plus(Duration.ofHours(4 * (24 + 24 + 48))).plus(Duration.ofDays(3)));

            scheduler.processDueDebits();
            assertThat(debitStatus(merchant, mandateId, debitId)).isEqualTo("notifying");
            runDebitWorkerWhenDue(debitId);
            Map<String, Object> ready = getDebit(merchant, mandateId, debitId);
            assertThat(str(ready, "status")).isEqualTo("ready");
            assertThat(Instant.parse(str(ready, "notified_at"))).isEqualTo(createdAt);
            assertThat(nextActionAt(debitId)).as("the due date comes after the notice period").isEqualTo(due);
            runDebitWorkerWhenDue(debitId);
            Map<String, Object> paying = getPayment(merchant, paymentId);
            assertThat(debitStatus(merchant, mandateId, debitId)).isEqualTo("executing");
            assertThat(str(paying, "status")).isEqualTo("processing");
            assertThat(str(paying, "latest_attempt.provider_reference")).isEqualTo(notificationReference(debitId));

            simulate(ALPHA, str(paying, "latest_attempt.provider_reference"), "success", false);

            assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
            assertThat(str(assertContract("GET", "/v1/mandates/{mandate_id}/debits/{debit_id}", null,
                    get(merchant, "/v1/mandates/" + mandateId + "/debits/" + debitId)).body(), "status")).isEqualTo("succeeded");
            assertThat(list(assertContract("GET", "/v1/mandates/{mandate_id}/debits", null,
                    get(merchant, "/v1/mandates/" + mandateId + "/debits")).body(), "data"))
                    .extracting(debit -> debit.get("id")).containsExactly(debitId);
            assertThat(ledgerBalances(merchant)).as("the authorization charge and the debit").containsEntry("psp_receivable", 100_000L);
            assertThat(transitions(mandateId, "MANDATE")).containsExactly("CREATED", "PENDING_AUTHORIZATION", "ACTIVE");
            assertThat(transitions(mandateId, "DEBIT"))
                    .containsExactly("SCHEDULED", "NOTIFYING", "READY", "EXECUTING", "SUCCEEDED");
            List<String> events = deliveredEventTypes(endpoint);
            assertThat(events).contains("mandate.activated");
            assertThat(events).filteredOn("payment.succeeded"::equals).as("authorization charge and debit").hasSize(2);
        }
    }

    @Test
    void enachMandatesRegisterWithoutAChargeAndDebitOnTheDueDateWithoutNotice() {
        TestMerchant merchant = createMerchant(ALPHA);
        Response created = createMandate(merchant, "enach", 5_000_000);
        assertThat(created.body()).doesNotContainKey("registration_payment_id");
        String mandateId = str(created.body(), "id");
        authorize(mandateId, "success");

        String debitId = createDebit(merchant, mandateId, 2_500_000, "emi_1");

        assertThat(debitStatus(merchant, mandateId, debitId)).as("no notice for eNACH").isEqualTo("ready");
        String reference = executeCycle(merchant, mandateId, debitId);
        simulate(ALPHA, reference, "success", false);
        Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
        assertThat(str(debit, "status")).isEqualTo("succeeded");
        assertThat(debit).doesNotContainKey("notified_at");
        assertThat(transitions(mandateId, "DEBIT")).containsExactly("READY", "EXECUTING", "SUCCEEDED");
    }

    @Test
    void creatingAMandateIsIdempotent() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> request = mandateRequest("upi_autopay", 500_000);
        String key = key();

        Response first = postMandate(merchant, request, key);
        Response replayed = postMandate(merchant, request, key);
        Response reused = postMandate(merchant, mandateRequest("upi_autopay", 600_000), key);

        assertThat(replayed.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(replayed.raw()).isEqualTo(first.raw());
        assertThat(str(reused.body(), "code")).isEqualTo("idempotency_key_reuse");
        assertThat(count("SELECT count(*) FROM mandates")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments")).as("one authorization charge").isEqualTo(1);
    }

    @Test
    void aRegistrationWhoseResponseWasLostIsFoundByThePoller() {
        TestMerchant merchant = createMerchant(ALPHA);
        Response created = createMandate(merchant, "upi_autopay", 500_001);
        String mandateId = str(created.body(), "id");
        assertThat(str(created.body(), "status")).isEqualTo("created");
        assertThat(created.body()).doesNotContainKey("next_action");

        clock.advance(Duration.ofSeconds(30));
        scheduler.processDueMandates();

        Map<String, Object> found = getMandate(merchant, mandateId);
        assertThat(str(found, "status")).isEqualTo("pending_authorization");
        assertThat(str(found, "next_action.type")).isEqualTo("redirect");
        assertThat(str(getPayment(merchant, str(found, "registration_payment_id")), "status")).isEqualTo("requires_action");
    }

    @Test
    void aRegistrationThePspNeverReceivedFails() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            String mandateId = str(createMandate(merchant, "upi_autopay", 500_005).body(), "id");

            clock.advance(Duration.ofSeconds(30));
            scheduler.processDueMandates();

            Map<String, Object> failed = getMandate(merchant, mandateId);
            assertThat(str(failed, "status")).isEqualTo("failed");
            assertThat(str(failed, "last_error.code")).isEqualTo("not_submitted");
            assertThat(deliveredEventTypes(endpoint)).containsExactly("mandate.failed");
        }
    }

    @Test
    void aMandateNotAuthorizedInTimeFailsAndIsCancelledAtThePsp() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> created = createMandate(merchant, "upi_autopay", 500_000).body();
        String mandateId = str(created, "id");
        clock.advance(Duration.ofHours(24).minusSeconds(1));
        scheduler.processDueMandates();
        assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("pending_authorization");

        clock.advance(Duration.ofSeconds(1));
        scheduler.processDueMandates();

        Map<String, Object> lapsed = getMandate(merchant, mandateId);
        assertThat(str(lapsed, "status")).isEqualTo("failed");
        assertThat(str(lapsed, "last_error.code")).isEqualTo("authorization_expired");
        assertThat(lapsed).doesNotContainKey("next_action");
        assertThat(mock(ALPHA).psp().findMandate(pspReference(mandateId), null).orElseThrow().state())
                .isEqualTo(MandateState.REVOKED);
        assertThat(str(getPayment(merchant, str(created, "registration_payment_id")), "status")).isEqualTo("cancelled");
        assertThat(authorize(mandateId, "success").status()).as("too late at the PSP as well").isEqualTo(409);
    }

    @Test
    void aRegistrationTheCustomerAuthorizedWaitsForTheBankPastTheWindow() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = str(createMandate(merchant, "enach", 500_000).body(), "id");

        assertThat(authorize(mandateId, "confirming").status()).isEqualTo(200);

        Map<String, Object> confirming = getMandate(merchant, mandateId);
        assertThat(str(confirming, "status")).isEqualTo("pending_authorization");
        assertThat(confirming).as("nothing left for the customer to do").doesNotContainKey("next_action");
        clock.advance(Duration.ofHours(25));
        scheduler.processDueMandates();
        assertThat(str(getMandate(merchant, mandateId), "status")).as("the bank is still confirming")
                .isEqualTo("pending_authorization");
        customerAction(mandateId, "active", true);
        assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("active");
    }

    @Test
    void aRejectedRegistrationFailsTheMandateAndItsCharge() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> created = createMandate(merchant, "card", 500_000).body();

        authorize(str(created, "id"), "failure");

        Map<String, Object> failed = getMandate(merchant, str(created, "id"));
        assertThat(str(failed, "status")).isEqualTo("failed");
        assertThat(str(failed, "last_error.code")).isEqualTo("mandate_rejected");
        assertThat(str(getPayment(merchant, str(created, "registration_payment_id")), "status")).isEqualTo("failed");
    }

    @Test
    void aCustomerRevocationByWebhookCancelsTheWaitingDebitSoItNeverRuns() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
            String debitId = createDebit(merchant, mandateId, 49_900, "inv_1");
            scheduler.processDueDebits();
            assertThat(debitStatus(merchant, mandateId, debitId)).isEqualTo("notifying");

            customerAction(mandateId, "revoked", true);

            assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("revoked");
            Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
            assertThat(str(debit, "status")).isEqualTo("cancelled");
            Map<String, Object> payment = getPayment(merchant, str(debit, "payment_id"));
            assertThat(str(payment, "status")).isEqualTo("cancelled");
            assertThat(str(payment, "cancellation_reason")).isEqualTo("mandate_revoked");
            clock.advance(Duration.ofDays(3));
            scheduler.processDueDebits();
            assertThat(num(getPayment(merchant, str(debit, "payment_id")), "attempt_count")).isZero();
            assertThat(deliveredEventTypes(endpoint)).contains("mandate.revoked", "payment.cancelled");
        }
    }

    @Test
    void aRevocationWhoseWebhookWasLostIsLearnedWhenTheNextDebitFails() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
        customerAction(mandateId, "revoked", false);
        String debitId = createDebit(merchant, mandateId, 49_900, "inv_1");

        scheduler.processDueDebits();

        Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
        assertThat(str(debit, "status")).as("the PSP refuses to notify on a revoked mandate").isEqualTo("failed");
        assertThat(str(debit, "last_error.code")).isEqualTo("notification_failed");
        assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("active");
        scheduler.processDueMandates();
        assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("revoked");
        assertThat(transitions(mandateId, "MANDATE")).last().isEqualTo("REVOKED");
    }

    @Test
    void merchantRevocationReachesThePspFirstAndReplays() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
            String debitId = createDebit(merchant, mandateId, 49_900, "inv_1");
            String key = key();

            Response revoked = assertContract("POST", "/v1/mandates/{mandate_id}/revoke", null,
                    post(merchant, "/v1/mandates/" + mandateId + "/revoke", key, null));

            assertThat(revoked.status()).isEqualTo(200);
            assertThat(str(revoked.body(), "status")).isEqualTo("revoked");
            assertThat(mock(ALPHA).psp().findMandate(pspReference(mandateId), null).orElseThrow().state())
                    .isEqualTo(MandateState.REVOKED);
            assertThat(debitStatus(merchant, mandateId, debitId)).isEqualTo("cancelled");
            Response replayed = post(merchant, "/v1/mandates/" + mandateId + "/revoke", key, null);
            assertThat(replayed.headers().firstValue("Idempotent-Replayed")).contains("true");
            assertThat(replayed.raw()).isEqualTo(revoked.raw());
            Response again = post(merchant, "/v1/mandates/" + mandateId + "/revoke", key(), null);
            assertThat(again.status()).isEqualTo(200);
            assertThat(str(again.body(), "status")).isEqualTo("revoked");
            assertThat(deliveredEventTypes(endpoint)).filteredOn("mandate.revoked"::equals).hasSize(1);
        }
    }

    @Test
    void revocationIsRefusedOnceAMandateFailedAndRetriedWhileThePspIsDown() {
        TestMerchant merchant = createMerchant(ALPHA);
        String rejected = str(createMandate(merchant, "card", 500_000).body(), "id");
        authorize(rejected, "failure");
        Response refused = assertContract("POST", "/v1/mandates/{mandate_id}/revoke", null,
                post(merchant, "/v1/mandates/" + rejected + "/revoke", key(), null));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(str(refused.body(), "code")).isEqualTo("mandate_invalid_state");

        String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
        String key = key();
        mock(ALPHA).psp().setAvailable(false);
        Response unavailable = assertContract("POST", "/v1/mandates/{mandate_id}/revoke", null,
                post(merchant, "/v1/mandates/" + mandateId + "/revoke", key, null));
        assertThat(unavailable.status()).isEqualTo(503);
        assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("active");

        mock(ALPHA).psp().setAvailable(true);
        Response retried = post(merchant, "/v1/mandates/" + mandateId + "/revoke", key, null);
        assertThat(retried.status()).as("the same key works once the PSP is back").isEqualTo(200);
        assertThat(str(retried.body(), "status")).isEqualTo("revoked");
    }

    @Test
    void aPausedMandateFailsItsDueDebitAndIsDebitedAgainOnceResumed() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
            String debitId = createDebit(merchant, mandateId, 49_900, "inv_1");

            customerAction(mandateId, "paused", true);
            assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("paused");
            scheduler.processDueDebits();

            Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
            assertThat(str(debit, "status")).isEqualTo("failed");
            assertThat(str(debit, "last_error.code")).isEqualTo("mandate_paused");
            assertThat(str(getPayment(merchant, str(debit, "payment_id")), "last_error.code")).isEqualTo("mandate_paused");
            Response refused = postDebit(merchant, mandateId, Map.of("amount", 49_900, "merchant_debit_id", "inv_2"));
            assertThat(str(refused.body(), "code")).isEqualTo("mandate_invalid_state");

            customerAction(mandateId, "active", true);
            assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("active");
            createDebit(merchant, mandateId, 49_900, "inv_2");
            assertThat(deliveredEventTypes(endpoint)).contains("mandate.paused", "mandate.resumed", "payment.failed")
                    .filteredOn("mandate.activated"::equals).as("a resume is not a second activation").hasSize(1);
        }
    }

    @Test
    void aMandateExpiresAtTheEndOfItsTermAndCancelsItsWaitingDebit() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            Instant endAt = clock.instant().plus(Duration.ofDays(3));
            Map<String, Object> request = mandateRequest("enach", 500_000);
            request.put("end_at", endAt.toString());
            String mandateId = str(postMandate(merchant, request, key()).body(), "id");
            authorize(mandateId, "success");
            Map<String, Object> afterEnd = new HashMap<>(Map.of("amount", 10_000, "merchant_debit_id", "late"));
            afterEnd.put("due_at", endAt.toString());
            assertThat(str(postDebit(merchant, mandateId, afterEnd).body(), "code")).isEqualTo("validation_error");
            Map<String, Object> beforeEnd = new HashMap<>(Map.of("amount", 10_000, "merchant_debit_id", "inv_1"));
            beforeEnd.put("due_at", endAt.minus(Duration.ofHours(1)).toString());
            String debitId = str(postDebit(merchant, mandateId, beforeEnd).body(), "id");

            clock.set(endAt.minusNanos(1_000));
            scheduler.processDueMandates();
            assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("active");
            clock.set(endAt);
            scheduler.processDueMandates();

            assertThat(str(getMandate(merchant, mandateId), "status")).isEqualTo("expired");
            assertThat(debitStatus(merchant, mandateId, debitId)).isEqualTo("cancelled");
            assertThat(deliveredEventTypes(endpoint)).contains("mandate.expired", "payment.cancelled");
        }
    }

    @Test
    void mandateRequestsAreValidated() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> aboveThePspMaximum = mandateRequest("upi_autopay", 10_000_001);
        Map<String, Object> dollars = mandateRequest("card", 500_000);
        dollars.put("currency", "USD");
        Map<String, Object> noPhone = mandateRequest("card", 500_000);
        noPhone.put("customer", Map.of("email", "asha@example.com"));
        Map<String, Object> startsLongAgo = mandateRequest("card", 500_000);
        startsLongAgo.put("start_at", clock.instant().minus(Duration.ofHours(2)).toString());
        Map<String, Object> endsBeforeItStarts = mandateRequest("card", 500_000);
        endsBeforeItStarts.put("end_at", clock.instant().minus(Duration.ofMinutes(1)).toString());
        Map<String, Object> endsTooLate = mandateRequest("card", 500_000);
        endsTooLate.put("end_at", clock.instant().plus(Duration.ofDays(31 * 366L)).toString());

        assertThat(str(assertContract("POST", "/v1/mandates", aboveThePspMaximum,
                postMandate(merchant, aboveThePspMaximum, key())).body(), "code")).isEqualTo("unsupported_payment_method");
        assertThat(str(assertContract("POST", "/v1/mandates", dollars, postMandate(merchant, dollars, key())).body(),
                "code")).isEqualTo("unsupported_currency");
        assertThat(str(assertContract("POST", "/v1/mandates", null, postMandate(merchant, noPhone, key())).body(),
                "code")).isEqualTo("validation_error");
        assertThat(str(postMandate(merchant, startsLongAgo, key()).body(), "code")).isEqualTo("validation_error");
        assertThat(str(postMandate(merchant, endsBeforeItStarts, key()).body(), "code")).isEqualTo("validation_error");
        assertThat(str(postMandate(merchant, endsTooLate, key()).body(), "code")).isEqualTo("validation_error");
        assertThat(count("SELECT count(*) FROM mandates")).isZero();
    }

    @Test
    void mandatesRegisterAtALinkedProviderWhoseCircuitIsClosed() {
        TestMerchant betaOnly = createMerchant(BETA);
        TestMerchant both = createMerchant(ALPHA, BETA);

        assertThat(str(createMandate(betaOnly, "upi_autopay", 500_000).body(), "provider")).isEqualTo(BETA);
        assertThat(str(createMandate(both, "upi_autopay", 500_000).body(), "provider")).isEqualTo(ALPHA);
        openCircuit(both, ALPHA);
        assertThat(str(createMandate(both, "upi_autopay", 500_000).body(), "provider")).isEqualTo(BETA);
        openCircuit(both, BETA);
        Response unavailable = assertContract("POST", "/v1/mandates", null,
                postMandate(both, mandateRequest("upi_autopay", 500_000), key()));
        assertThat(unavailable.status()).isEqualTo(503);
        assertThat(str(unavailable.body(), "code")).isEqualTo("no_provider_available");
    }

    @Test
    void mandatesAndTheirDebitsBelongToOneMerchant() {
        TestMerchant owner = createMerchant(ALPHA);
        TestMerchant other = createMerchant(ALPHA);
        String mandateId = activeMandate(owner, "upi_autopay", 500_000);
        String debitId = createDebit(owner, mandateId, 49_900, "inv_1");

        assertThat(assertContract("GET", "/v1/mandates/{mandate_id}", null, get(other, "/v1/mandates/" + mandateId))
                .status()).isEqualTo(404);
        assertThat(get(other, "/v1/mandates/" + mandateId + "/debits/" + debitId).status()).isEqualTo(404);
        assertThat(postDebit(other, mandateId, Map.of("amount", 100, "merchant_debit_id", "x")).status()).isEqualTo(404);
        assertThat(post(other, "/v1/mandates/" + mandateId + "/revoke", key(), null).status()).isEqualTo(404);

        Map<String, Object> forged = new HashMap<>();
        forged.put("event_id", "evt_forged_mandate");
        forged.put("type", "mandate.updated");
        forged.put("provider_reference", "mock_alpha_mdt_forged");
        forged.put("merchant_reference", mandateId);
        forged.put("status", "revoked");
        assertThat(pspWebhook(other, forged).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM provider_webhook_events WHERE provider_event_id = 'evt_forged_mandate'")
                .query(String.class).single()).isEqualTo("IGNORED");
        assertThat(str(getMandate(owner, mandateId), "status")).isEqualTo("active");
    }

    @Test
    void mandatePaymentsAreConfirmedAndCancelledOnlyByTheGateway() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
        String paymentId = str(getDebit(merchant, mandateId, createDebit(merchant, mandateId, 49_900, "inv_1")),
                "payment_id");

        Response confirm = confirm(merchant, paymentId, upi("intent"));
        Response cancel = post(merchant, "/v1/payments/" + paymentId + "/cancel", key(), null);
        Response checkout = post(merchant, "/v1/checkout-sessions", key(), Map.of("payment_id", paymentId));

        assertThat(confirm.status()).isEqualTo(409);
        assertThat(str(confirm.body(), "code")).isEqualTo("payment_invalid_state");
        assertThat(cancel.status()).isEqualTo(409);
        assertThat(checkout.status()).isEqualTo(409);
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("requires_payment_method");
    }
}
