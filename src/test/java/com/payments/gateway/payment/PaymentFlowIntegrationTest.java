package com.payments.gateway.payment;

import com.payments.gateway.support.FakeMerchantEndpoint;
import com.payments.gateway.support.IntegrationTest;
import com.payments.gateway.webhook.outbound.WebhookSigner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentFlowIntegrationTest extends IntegrationTest {

    @Test
    void upiIntentCompletesViaSignedProviderWebhookAndNotifiesMerchant() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            Map<String, Object> payment = createPayment(merchant, 49_900, "automatic");
            String paymentId = str(payment, "id");
            assertThat(str(payment, "status")).isEqualTo("requires_payment_method");

            Response confirmed = confirm(merchant, paymentId, upi("intent"));

            assertThat(confirmed.status()).isEqualTo(200);
            assertThat(str(confirmed.body(), "status")).isEqualTo("requires_action");
            assertThat(str(confirmed.body(), "next_action.type")).isEqualTo("upi_intent");
            assertThat(str(confirmed.body(), "next_action.upi_uri")).startsWith("upi://pay?").contains("am=499.00");
            String reference = str(confirmed.body(), "latest_attempt.provider_reference");

            assertThat(simulate(ALPHA, reference, "success", false).status()).isEqualTo(200);

            Map<String, Object> succeeded = getPayment(merchant, paymentId);
            assertThat(str(succeeded, "status")).isEqualTo("succeeded");
            assertThat(num(succeeded, "amount_captured")).isEqualTo(49_900);
            assertThat(str(succeeded, "latest_attempt.status")).isEqualTo("succeeded");
            assertThat(str(succeeded, "latest_attempt.card")).as("UPI payments have no card details").isNull();

            deliveryWorker.deliverDue();
            List<FakeMerchantEndpoint.Received> received = endpoint.received();
            assertThat(received).hasSize(1);
            FakeMerchantEndpoint.Received webhook = received.getFirst();
            assertThat(webhook.headers().get("pg-event-type")).isEqualTo("payment.succeeded");
            assertThat(WebhookSigner.verify(webhook.headers().get("pg-signature"), merchant.webhookSecret(), webhook.body(),
                    clock.instant().getEpochSecond(), 300)).isTrue();
            assertThat(webhook.body()).contains(paymentId).contains("\"status\":\"succeeded\"");

            assertThat(count("SELECT count(*) FROM payment_transitions WHERE payment_id = ?", paymentId)).isGreaterThanOrEqualTo(6);
        }
    }

    @Test
    void cardManualCaptureAuthorizeThenCapture() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);
        String paymentId = str(createPayment(merchant, 250_000, "manual"), "id");

        Response confirmed = confirm(merchant, paymentId, card());
        assertThat(str(confirmed.body(), "status")).isEqualTo("requires_action");
        assertThat(str(confirmed.body(), "next_action.type")).isEqualTo("redirect");
        assertThat(str(confirmed.body(), "next_action.url")).contains("/simulator/").contains("/checkout/");

        String provider = str(confirmed.body(), "latest_attempt.provider");
        simulate(provider, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        Map<String, Object> authorized = getPayment(merchant, paymentId);
        assertThat(str(authorized, "status")).isEqualTo("authorized");
        assertThat(str(authorized, "authorization_expires_at")).isNotNull();
        assertThat(str(authorized, "latest_attempt.card.network")).isEqualTo("visa");
        assertThat(str(authorized, "latest_attempt.card.last4")).isEqualTo("1111");
        assertThat(count("SELECT count(*) FROM payment_attempts WHERE payment_id = ? AND card_last4 = '1111'", paymentId)).isEqualTo(1);

        Response captured = post(merchant, "/v1/payments/" + paymentId + "/capture", "cap-" + paymentId, Map.of());

        assertThat(captured.status()).isEqualTo(200);
        assertThat(str(captured.body(), "status")).isEqualTo("succeeded");
        assertThat(num(captured.body(), "amount_captured")).isEqualTo(250_000);
    }

    @Test
    void cancellingAnAuthorizedPaymentVoidsTheAuthorization() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 150_000, "manual"), "id");
        Response confirmed = confirm(merchant, paymentId, card());
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);

        Response cancelled = post(merchant, "/v1/payments/" + paymentId + "/cancel", "cancel-" + paymentId,
                Map.of("reason", "requested_by_customer"));

        assertThat(str(cancelled.body(), "status")).isEqualTo("cancelled");
        assertThat(str(cancelled.body(), "latest_attempt.status")).isEqualTo("voided");
        assertThat(str(cancelled.body(), "cancellation_reason")).isEqualTo("requested_by_customer");
    }

    @Test
    void issuerDeclineReturnsPaymentToRequiresPaymentMethod() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_003, "automatic"), "id");

        Response declined = confirm(merchant, paymentId, card());

        assertThat(str(declined.body(), "status")).isEqualTo("requires_payment_method");
        assertThat(str(declined.body(), "last_error.code")).isEqualTo("transaction_declined");
        assertThat(str(declined.body(), "last_error.category")).isEqualTo("issuer");
        assertThat(count("SELECT count(*) FROM merchant_events WHERE type = 'payment.attempt_failed'")).isEqualTo(1);
    }

    @Test
    void failedAttemptCanBeRetriedWithAnotherMethod() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
        Response collect = confirm(merchant, paymentId, Map.of("type", "upi", "upi", Map.of("flow", "collect", "vpa", "buyer@okbank")));
        simulate(ALPHA, str(collect.body(), "latest_attempt.provider_reference"), "failure", false);

        Response retried = confirm(merchant, paymentId, netbanking("HDFC"));
        assertThat(str(retried.body(), "status")).isEqualTo("requires_action");
        assertThat(num(retried.body(), "attempt_count")).isEqualTo(2);
        simulate(ALPHA, str(retried.body(), "latest_attempt.provider_reference"), "success", false);

        Map<String, Object> succeeded = getPayment(merchant, paymentId);
        assertThat(str(succeeded, "status")).isEqualTo("succeeded");
        assertThat(str(succeeded, "latest_attempt.method")).isEqualTo("netbanking");
    }

    @Test
    void providerTimeoutIsUnknownAndResolvedByStatusCheck() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 20_001, "automatic"), "id");

        Response confirmed = confirm(merchant, paymentId, card());

        assertThat(str(confirmed.body(), "status")).isEqualTo("processing");
        assertThat(str(confirmed.body(), "latest_attempt.status")).isEqualTo("unknown");
        assertThat(num(confirmed.body(), "attempt_count")).as("no failover on unknown outcome").isEqualTo(1);

        resolveStatuses(Duration.ofSeconds(6), 1);

        Map<String, Object> resolved = getPayment(merchant, paymentId);
        assertThat(str(resolved, "status")).isEqualTo("succeeded");
        assertThat(num(resolved, "attempt_count")).isEqualTo(1);
    }

    @Test
    void timeoutThatNeverReachedProviderFailsSafelyAfterStatusCheck() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 20_005, "automatic"), "id");

        assertThat(str(confirm(merchant, paymentId, card()).body(), "latest_attempt.status")).isEqualTo("unknown");
        resolveStatuses(Duration.ofSeconds(6), 1);

        Map<String, Object> resolved = getPayment(merchant, paymentId);
        assertThat(str(resolved, "status")).isEqualTo("requires_payment_method");
        assertThat(str(resolved, "latest_attempt.failure.code")).isEqualTo("not_submitted");
    }

    @Test
    void missedWebhookIsRecoveredByPolling() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 30_004, "automatic"), "id");

        assertThat(str(confirm(merchant, paymentId, upi("qr")).body(), "latest_attempt.status")).isEqualTo("pending");
        resolveStatuses(Duration.ofSeconds(6), 2);

        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
    }

    @Test
    void failsOverWhenFirstProviderIsDefinitelyUnavailable() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);
        admin("POST", "/admin/v1/routing-rules", Map.of("name", "prefer alpha", "priority", 1, "strategy", "priority",
                "targets", List.of(Map.of("provider", ALPHA), Map.of("provider", BETA))));
        send("POST", "/simulator/" + ALPHA + "/availability", Map.of(), Map.of("available", false));
        String paymentId = str(createPayment(merchant, 40_000, "automatic"), "id");

        Response confirmed = confirm(merchant, paymentId, upi("intent"));

        assertThat(str(confirmed.body(), "status")).isEqualTo("requires_action");
        assertThat(num(confirmed.body(), "attempt_count")).isEqualTo(2);
        assertThat(str(confirmed.body(), "latest_attempt.provider")).isEqualTo(BETA);
        assertThat(count("SELECT count(*) FROM payment_attempts WHERE payment_id = ? AND failure_category = 'PROVIDER_UNAVAILABLE'", paymentId))
                .isEqualTo(1);
    }

    @Test
    void duplicateProviderWebhooksAreAppliedOnce() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 12_300, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, upi("qr"));
        assertThat(str(confirmed.body(), "next_action.type")).isEqualTo("display_qr");

        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", true);

        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
        assertThat(count("SELECT count(*) FROM provider_webhook_events")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM merchant_events WHERE type = 'payment.succeeded'")).isEqualTo(1);
    }

    @Test
    void lateSuccessAfterExpiryIsRefundedAutomatically() {
        TestMerchant merchant = createMerchantWith(null, "auto_refund", ALPHA);
        String paymentId = str(createPayment(merchant, 55_500, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, upi("intent"));

        clock.advance(Duration.ofMinutes(16));
        assertThat(expiryJob.expireDue()).isEqualTo(1);
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("expired");

        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);

        Map<String, Object> payment = getPayment(merchant, paymentId);
        assertThat(str(payment, "status")).isEqualTo("expired");
        List<Map<String, Object>> refunds = list(get(merchant, "/v1/payments/" + paymentId + "/refunds").body(), "data");
        assertThat(refunds).hasSize(1);
        assertThat(str(refunds.getFirst(), "initiated_by")).isEqualTo("system_late_success");
        assertThat(str(refunds.getFirst(), "status")).isEqualTo("succeeded");
        assertThat(num(refunds.getFirst(), "amount")).isEqualTo(55_500);
    }

    @Test
    void lateSuccessIsAcceptedWhenMerchantOptsIn() {
        TestMerchant merchant = createMerchantWith(null, "accept", ALPHA);
        String paymentId = str(createPayment(merchant, 55_500, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, upi("intent"));
        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireDue();

        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);

        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
        assertThat(list(get(merchant, "/v1/payments/" + paymentId + "/refunds").body(), "data")).isEmpty();
    }

    @Test
    void confirmingAfterExpiryIsRejected() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
        clock.advance(Duration.ofMinutes(20));

        Response response = confirm(merchant, paymentId, upi("intent"));

        assertThat(response.status()).isEqualTo(409);
        assertThat(str(response.body(), "code")).isEqualTo("payment_invalid_state");
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("expired");
    }

    @Test
    void unsupportedMethodForLinkedProvidersIsRejected() {
        TestMerchant merchant = createMerchant(BETA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");

        Response response = confirm(merchant, paymentId, netbanking("HDFC"));

        assertThat(response.status()).isEqualTo(422);
        assertThat(str(response.body(), "code")).isEqualTo("unsupported_payment_method");
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("requires_payment_method");
    }

    @Test
    void riskEngineBlocksBlocklistedVpa() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");

        Response response = confirm(merchant, paymentId, Map.of("type", "upi", "upi", Map.of("flow", "collect", "vpa", "fraud@okbank")));

        assertThat(response.status()).isEqualTo(200);
        assertThat(str(response.body(), "status")).isEqualTo("failed");
        assertThat(str(response.body(), "last_error.code")).isEqualTo("risk_blocked");
        assertThat(num(response.body(), "attempt_count")).isZero();
    }

    @Test
    void upiCollectAwaitsCustomerApproval() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");

        Response response = confirm(merchant, paymentId, Map.of("type", "upi", "upi", Map.of("flow", "collect", "vpa", "buyer@okbank")));

        assertThat(str(response.body(), "status")).isEqualTo("requires_action");
        assertThat(str(response.body(), "next_action.type")).isEqualTo("await_approval");
        simulate(ALPHA, str(response.body(), "latest_attempt.provider_reference"), "failure", false);
        Map<String, Object> failed = getPayment(merchant, paymentId);
        assertThat(str(failed, "status")).isEqualTo("requires_payment_method");
        assertThat(str(failed, "last_error.code")).isEqualTo("customer_declined");
    }
}
