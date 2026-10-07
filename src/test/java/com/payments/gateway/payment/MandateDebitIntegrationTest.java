package com.payments.gateway.payment;

import com.payments.gateway.support.FakeMerchantEndpoint;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** Debits of a mandate: limits, notification, retries and execution (requirements §8.1, NFR-19, LLD §18.5). */
class MandateDebitIntegrationTest extends MandateTestSupport {

    @Test
    void debitsAboveTheMandateOrTheFrictionlessLimitAreRefused() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "upi_autopay", 2_000_000);
        Map<String, Object> aboveMax = Map.of("amount", 2_000_001, "merchant_debit_id", "inv_1");
        Map<String, Object> aboveFrictionless = Map.of("amount", 1_500_001, "merchant_debit_id", "inv_1");

        Response tooLarge = assertContract("POST", "/v1/mandates/{mandate_id}/debits", aboveMax,
                postDebit(merchant, mandateId, aboveMax));
        Response needsAuthentication = postDebit(merchant, mandateId, aboveFrictionless);

        assertThat(tooLarge.status()).isEqualTo(422);
        assertThat(str(tooLarge.body(), "code")).isEqualTo("amount_exceeds_mandate_limit");
        assertThat(str(needsAuthentication.body(), "code")).isEqualTo("amount_exceeds_mandate_limit");
        assertThat(str(needsAuthentication.body(), "detail")).contains("1500000");
        Response raised = admin("PATCH", "/admin/v1/merchants/" + merchant.id(), Map.of("mandate_debit_limit", 1_600_000));
        assertThat(raised.status()).as(raised.raw()).isEqualTo(200);
        assertThat(num(raised.body(), "mandate_debit_limit")).isEqualTo(1_600_000);
        assertThat(postDebit(merchant, mandateId, Map.of("amount", 1_600_001, "merchant_debit_id", "inv_1")).status())
                .isEqualTo(422);
        createDebit(merchant, mandateId, 1_600_000, "inv_1");
        assertThat(count("SELECT count(*) FROM mandate_debits")).isEqualTo(1);
    }

    @Test
    void enachDebitsHaveNoFrictionlessLimit() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "enach", 5_000_000);

        String debitId = createDebit(merchant, mandateId, 5_000_000, "emi_1");

        assertThat(debitStatus(merchant, mandateId, debitId)).isEqualTo("ready");
    }

    @Test
    void aMandateHasOneDebitInProgressAndEachMerchantDebitIdOnce() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
        String debitId = createDebit(merchant, mandateId, 49_900, "inv_1");

        Response reused = assertContract("POST", "/v1/mandates/{mandate_id}/debits", null,
                postDebit(merchant, mandateId, Map.of("amount", 49_900, "merchant_debit_id", "inv_1")));
        Response second = postDebit(merchant, mandateId, Map.of("amount", 49_900, "merchant_debit_id", "inv_2"));

        assertThat(reused.status()).isEqualTo(409);
        assertThat(str(reused.body(), "code")).isEqualTo("mandate_debit_already_exists");
        assertThat(second.status()).isEqualTo(409);
        assertThat(str(second.body(), "code")).isEqualTo("mandate_invalid_state");

        Response cancelled = assertContract("POST", "/v1/mandates/{mandate_id}/debits/{debit_id}/cancel", null,
                post(merchant, "/v1/mandates/" + mandateId + "/debits/" + debitId + "/cancel", key(), null));
        assertThat(str(cancelled.body(), "status")).isEqualTo("cancelled");
        assertThat(str(getPayment(merchant, str(cancelled.body(), "payment_id")), "status")).isEqualTo("cancelled");
        assertThat(str(getPayment(merchant, str(cancelled.body(), "payment_id")), "cancellation_reason"))
                .isEqualTo("debit_cancelled");
        Response cancelledAgain = post(merchant, "/v1/mandates/" + mandateId + "/debits/" + debitId + "/cancel", key(), null);
        assertThat(cancelledAgain.status()).as("a cancelled debit stays cancelled").isEqualTo(200);
        assertThat(str(cancelledAgain.body(), "status")).isEqualTo("cancelled");
        assertThat(postDebit(merchant, mandateId, Map.of("amount", 49_900, "merchant_debit_id", "inv_1")).status())
                .as("a cancelled debit keeps its merchant_debit_id").isEqualTo(409);
        createDebit(merchant, mandateId, 49_900, "inv_2");
    }

    @Test
    void aDebitCanBeCancelledOnlyBeforeItExecutes() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "enach", 500_000);
        String debitId = createDebit(merchant, mandateId, 49_900, "emi_1");
        executeCycle(merchant, mandateId, debitId);

        Response refused = assertContract("POST", "/v1/mandates/{mandate_id}/debits/{debit_id}/cancel", null,
                post(merchant, "/v1/mandates/" + mandateId + "/debits/" + debitId + "/cancel", key(), null));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(str(refused.body(), "code")).isEqualTo("mandate_invalid_state");
        assertThat(debitStatus(merchant, mandateId, debitId)).isEqualTo("executing");
    }

    @Test
    void debitsNeedAnActiveMandateAndADueDateWithinItsTerm() {
        TestMerchant merchant = createMerchant(ALPHA);
        String pending = str(createMandate(merchant, "upi_autopay", 500_000).body(), "id");
        String active = activeMandate(merchant, "upi_autopay", 500_000);
        Map<String, Object> beforeStart = new HashMap<>(Map.of("amount", 49_900, "merchant_debit_id", "inv_1"));
        beforeStart.put("due_at", clock.instant().minus(Duration.ofDays(1)).toString());

        Response notActive = postDebit(merchant, pending, Map.of("amount", 49_900, "merchant_debit_id", "inv_1"));
        Response outsideTerm = postDebit(merchant, active, beforeStart);

        assertThat(notActive.status()).isEqualTo(409);
        assertThat(str(notActive.body(), "code")).isEqualTo("mandate_invalid_state");
        assertThat(outsideTerm.status()).isEqualTo(400);
        assertThat(str(outsideTerm.body(), "code")).isEqualTo("validation_error");
    }

    @Test
    void aFailedDebitIsRetriedADayLaterWithANewNotificationUntilTheAttemptLimit() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
            String debitId = createDebit(merchant, mandateId, 49_900, "inv_1");
            String paymentId = str(getDebit(merchant, mandateId, debitId), "payment_id");

            for (int cycle = 1; cycle <= 4; cycle++) {
                String reference = executeCycle(merchant, mandateId, debitId);
                assertThat(num(getDebit(merchant, mandateId, debitId), "cycle")).isEqualTo(cycle);
                Instant failedAt = clock.instant();
                simulate(ALPHA, reference, "failure", false);
                if (cycle < 4) {
                    Map<String, Object> retry = getDebit(merchant, mandateId, debitId);
                    assertThat(str(retry, "status")).isEqualTo("scheduled");
                    assertThat(Instant.parse(str(retry, "not_before"))).isEqualTo(failedAt.plus(Duration.ofHours(24)));
                    assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("requires_payment_method");
                }
            }

            Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
            assertThat(str(debit, "status")).isEqualTo("failed");
            assertThat(str(debit, "last_error.code")).isEqualTo("customer_declined");
            Map<String, Object> payment = getPayment(merchant, paymentId);
            assertThat(str(payment, "status")).isEqualTo("failed");
            assertThat(num(payment, "attempt_count")).isEqualTo(4);
            assertThat(jdbc.sql("SELECT DISTINCT provider_reference FROM payment_attempts WHERE payment_id = ?")
                    .param(1, paymentId).query(String.class).list()).as("one notification per cycle").hasSize(4);
            List<String> events = deliveredEventTypes(endpoint);
            assertThat(events).filteredOn("payment.attempt_failed"::equals).hasSize(3);
            assertThat(events).filteredOn("payment.failed"::equals).hasSize(1);
        }
    }

    @Test
    void aRetriedDebitThatSucceedsEndsTheCycles() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "card", 500_000);
        String debitId = createDebit(merchant, mandateId, 49_900, "inv_1");
        simulate(ALPHA, executeCycle(merchant, mandateId, debitId), "failure", false);

        simulate(ALPHA, executeCycle(merchant, mandateId, debitId), "success", false);

        Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
        assertThat(str(debit, "status")).isEqualTo("succeeded");
        assertThat(num(debit, "cycle")).isEqualTo(2);
        assertThat(str(getPayment(merchant, str(debit, "payment_id")), "status")).isEqualTo("succeeded");
    }

    @Test
    void aNotificationTheCustomerNeverReceivesFailsTheDebitAndItsPayment() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
        String debitId = createDebit(merchant, mandateId, 49_906, "inv_1");

        scheduler.processDueDebits();
        runDebitWorkerWhenDue(debitId);

        Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
        assertThat(str(debit, "status")).isEqualTo("failed");
        assertThat(str(debit, "last_error.code")).isEqualTo("notification_failed");
        Map<String, Object> payment = getPayment(merchant, str(debit, "payment_id"));
        assertThat(str(payment, "status")).isEqualTo("failed");
        assertThat(str(payment, "last_error.code")).isEqualTo("notification_failed");
        assertThat(num(payment, "attempt_count")).as("nothing was debited").isZero();
    }

    @Test
    void aNotificationThatIsNeverConfirmedGivesUpAfterTheTimeout() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
        String debitId = createDebit(merchant, mandateId, 49_900, "inv_1");
        Instant notBefore = Instant.parse(str(getDebit(merchant, mandateId, debitId), "not_before"));
        mock(ALPHA).psp().setAvailable(false);
        scheduler.processDueDebits();
        assertThat(debitStatus(merchant, mandateId, debitId)).as("asked again later").isEqualTo("scheduled");
        assertThat(nextActionAt(debitId)).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));

        clock.set(notBefore.plus(Duration.ofHours(48)));
        scheduler.processDueDebits();

        Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
        assertThat(str(debit, "status")).isEqualTo("failed");
        assertThat(str(debit, "last_error.code")).isEqualTo("notification_not_delivered");
    }

    @Test
    void aNotificationWebhookMakesTheDebitReady() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "upi_autopay", 500_000);
        String debitId = createDebit(merchant, mandateId, 49_900, "inv_1");
        scheduler.processDueDebits();
        Instant deliveredAt = clock.instant().plus(Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(2));
        Map<String, Object> delivered = new HashMap<>();
        delivered.put("event_id", "evt_ntf_1");
        delivered.put("type", "notification.updated");
        delivered.put("provider_reference", notificationReference(debitId));
        delivered.put("merchant_reference", debitId + ".1");
        delivered.put("status", "delivered");
        delivered.put("delivered_at", deliveredAt.toString());
        Map<String, Object> unknown = new HashMap<>(delivered);
        unknown.put("event_id", "evt_ntf_unknown");
        unknown.put("provider_reference", "mock_alpha_ntf_unknown");

        assertThat(pspWebhook(merchant, delivered).status()).isEqualTo(200);
        assertThat(pspWebhook(merchant, unknown).status()).isEqualTo(200);

        Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
        assertThat(str(debit, "status")).isEqualTo("ready");
        assertThat(Instant.parse(str(debit, "notified_at"))).isEqualTo(deliveredAt);
        assertThat(jdbc.sql("SELECT status FROM provider_webhook_events WHERE provider_event_id = 'evt_ntf_unknown'")
                .query(String.class).single()).isEqualTo("IGNORED");
    }

    @Test
    void aReadyDebitOfAPausedMandateFailsInsteadOfExecuting() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "enach", 500_000);
        String debitId = createDebit(merchant, mandateId, 49_900, "emi_1");
        customerAction(mandateId, "paused", true);

        scheduler.processDueDebits();

        Map<String, Object> debit = getDebit(merchant, mandateId, debitId);
        assertThat(str(debit, "status")).isEqualTo("failed");
        assertThat(str(debit, "last_error.code")).isEqualTo("mandate_paused");
        assertThat(num(getPayment(merchant, str(debit, "payment_id")), "attempt_count")).isZero();
    }

    @Test
    void aDebitDueWhileThePspCircuitIsOpenWaitsWithoutUsingARetry() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "enach", 500_000);
        String debitId = createDebit(merchant, mandateId, 49_900, "emi_1");
        String paymentId = str(getDebit(merchant, mandateId, debitId), "payment_id");
        openCircuit(merchant, ALPHA);

        scheduler.processDueDebits();

        assertThat(debitStatus(merchant, mandateId, debitId)).isEqualTo("ready");
        assertThat(num(getPayment(merchant, paymentId), "attempt_count")).isZero();
        assertThat(nextActionAt(debitId)).isEqualTo(clock.instant().plus(Duration.ofMinutes(1)));
        providerClient.resetCircuits();
        runDebitWorkerWhenDue(debitId);
        assertThat(debitStatus(merchant, mandateId, debitId)).isEqualTo("executing");
    }

    @Test
    void aDebitWhoseExecutionTimedOutIsResolvedByTheStatusResolver() {
        TestMerchant merchant = createMerchant(ALPHA);
        String mandateId = activeMandate(merchant, "enach", 500_000);
        String debitId = createDebit(merchant, mandateId, 49_901, "emi_1");
        String paymentId = str(getDebit(merchant, mandateId, debitId), "payment_id");

        scheduler.processDueDebits();
        assertThat(str(getPayment(merchant, paymentId), "latest_attempt.status")).isEqualTo("unknown");
        resolveStatuses(Duration.ofSeconds(30), 3);

        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
        assertThat(debitStatus(merchant, mandateId, debitId)).isEqualTo("succeeded");
    }
}
