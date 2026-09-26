package com.payments.gateway.payment;

import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewQueueIntegrationTest extends IntegrationTest {

    @Test
    void amountMismatchIsQueuedWithItsReasonAndResolvingIsAuditedButMovesNoMoney() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, upi("intent"));
        String attemptId = str(confirmed.body(), "latest_attempt.id");

        Response webhook = pspWebhook(merchant, Map.of("event_id", "evt_short_paid", "type", "payment.updated",
                "provider_reference", str(confirmed.body(), "latest_attempt.provider_reference"),
                "merchant_reference", attemptId, "status", "captured", "amount", 9_000, "currency", "INR"));

        assertThat(webhook.status()).isEqualTo(200);
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("requires_action");
        Map<String, Object> item = single(queue(merchant, null));
        assertThat(str(item, "kind")).isEqualTo("attempt");
        assertThat(str(item, "id")).isEqualTo(attemptId);
        assertThat(str(item, "payment_id")).isEqualTo(paymentId);
        assertThat(str(item, "status")).isEqualTo("requires_action");
        assertThat(num(item, "amount")).isEqualTo(10_000);
        assertThat(item.get("reasons")).isEqualTo(List.of("amount_mismatch"));
        assertThat(item).containsKey("flagged_at");

        Response resolved = admin("POST", "/admin/v1/reviews/attempts/" + attemptId + "/resolve",
                Map.of("note", "PSP confirms a short payment; customer contacted"));

        assertThat(resolved.status()).as(resolved.raw()).isEqualTo(200);
        assertThat(resolved.body().get("open")).isEqualTo(false);
        assertThat(resolved.body().get("reasons")).isEqualTo(List.of("amount_mismatch"));
        assertThat(queue(merchant, null)).isEmpty();
        assertThat(str(getPayment(merchant, paymentId), "status")).as("resolving never moves money").isEqualTo("requires_action");
        assertThat(jdbc.sql("SELECT details->>'note' FROM audit_log WHERE action = 'review.resolved' AND resource_id = ?")
                .param(1, attemptId).query(String.class).single()).isEqualTo("PSP confirms a short payment; customer contacted");

        assertThat(admin("POST", "/admin/v1/reviews/attempts/" + attemptId + "/resolve", Map.of("note", "again")).status())
                .as("already resolved").isEqualTo(409);
        assertThat(admin("POST", "/admin/v1/reviews/attempts/att_missing/resolve", Map.of("note", "x")).status()).isEqualTo(404);
        assertThat(admin("POST", "/admin/v1/reviews/attempts/" + attemptId + "/resolve", Map.of("note", " ")).status()).isEqualTo(400);
        assertThat(admin("GET", "/admin/v1/reviews?kind=payment", null).status()).isEqualTo(400);
    }

    @Test
    void refundContradictedByTheProviderIsQueuedAndResolvable() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 10_000);
        Response refund = post(merchant, "/v1/payments/" + str(payment, "id") + "/refunds", UUID.randomUUID().toString(),
                Map.of("amount", 5_000));
        assertThat(str(refund.body(), "status")).isEqualTo("succeeded");
        String refundId = str(refund.body(), "id");
        String reference = jdbc.sql("SELECT provider_reference FROM refunds WHERE id = ?").param(1, refundId)
                .query(String.class).single();

        Response webhook = pspWebhook(merchant, Map.of("event_id", "evt_refund_reversed", "type", "refund.updated",
                "provider_reference", reference, "merchant_reference", refundId, "status", "failed",
                "failure_code", "refund_reversed", "failure_message", "Beneficiary bank returned the refund"));

        assertThat(webhook.status()).isEqualTo(200);
        assertThat(str(get(merchant, "/v1/refunds/" + refundId).body(), "status")).isEqualTo("succeeded");
        assertThat(queue(merchant, "attempt")).isEmpty();
        Map<String, Object> item = single(queue(merchant, "refund"));
        assertThat(str(item, "id")).isEqualTo(refundId);
        assertThat(item.get("reasons")).isEqualTo(List.of("provider_conflict"));

        Response resolved = admin("POST", "/admin/v1/reviews/refunds/" + refundId + "/resolve",
                Map.of("note", "Reversal confirmed with PSP; re-refund via bank transfer"));

        assertThat(resolved.status()).as(resolved.raw()).isEqualTo(200);
        assertThat(queue(merchant, null)).isEmpty();
        assertThat(str(get(merchant, "/v1/refunds/" + refundId).body(), "status")).isEqualTo("succeeded");
    }

    @Test
    void attemptTheProviderCannotConfirmForSeventyTwoHoursIsQueued() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_001, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, upi("intent"));
        assertThat(str(confirmed.body(), "latest_attempt.status")).isEqualTo("unknown");
        alpha().psp().setAvailable(false);

        resolveStatuses(Duration.ofHours(6), 11);
        assertThat(queue(merchant, null)).as("still inside the 72h window").isEmpty();
        resolveStatuses(Duration.ofHours(6), 2);

        Map<String, Object> item = single(queue(merchant, null));
        assertThat(item.get("reasons")).isEqualTo(List.of("status_unresolved"));
        assertThat(str(item, "status")).isEqualTo("unknown");
        assertThat(jdbc.sql("SELECT next_status_check_at IS NULL FROM payment_attempts WHERE payment_id = ?")
                .param(1, paymentId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void riskReviewLetsThePaymentProceedButQueuesItWithTheRiskReasons() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 25_000_000, "automatic"), "id");

        Response confirmed = confirm(merchant, paymentId, card());

        assertThat(confirmed.status()).as(confirmed.raw()).isEqualTo(200);
        assertThat(str(confirmed.body(), "status")).isEqualTo("requires_action");
        Map<String, Object> item = single(queue(merchant, null));
        assertThat(str(item, "id")).isEqualTo(str(confirmed.body(), "latest_attempt.id"));
        assertThat(item.get("reasons")).isEqualTo(List.of("risk_review"));
        assertThat(item.get("risk_reasons")).isEqualTo(List.of("amount_above_review_threshold"));
        assertThat(jdbc.sql("SELECT risk_outcome FROM payment_attempts WHERE payment_id = ?").param(1, paymentId)
                .query(String.class).single()).isEqualTo("REVIEW");
    }

    @Test
    void riskReviewFollowsThePaymentToTheFailoverAttempt() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);
        alpha().psp().setAvailable(false);
        String paymentId = str(createPayment(merchant, 25_000_000, "automatic"), "id");

        Response confirmed = confirm(merchant, paymentId, card());

        assertThat(num(confirmed.body(), "attempt_count")).isEqualTo(2);
        assertThat(str(confirmed.body(), "latest_attempt.provider")).isEqualTo(BETA);
        Map<String, Object> item = single(queue(merchant, null));
        assertThat(str(item, "id")).isEqualTo(str(confirmed.body(), "latest_attempt.id"));
        assertThat(item.get("risk_reasons")).isEqualTo(List.of("amount_above_review_threshold"));
        assertThat(jdbc.sql("SELECT DISTINCT risk_outcome FROM payment_attempts WHERE payment_id = ?").param(1, paymentId)
                .query(String.class).list()).containsExactly("REVIEW");
    }

    private List<Map<String, Object>> queue(TestMerchant merchant, String kind) {
        Response response = admin("GET", "/admin/v1/reviews?merchant_id=" + merchant.id()
                + (kind == null ? "" : "&kind=" + kind), null);
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        return list(response.body(), "data");
    }

    private static Map<String, Object> single(List<Map<String, Object>> items) {
        assertThat(items).hasSize(1);
        return items.getFirst();
    }

    private Response pspWebhook(TestMerchant merchant, Map<String, Object> event) {
        String accountId = jdbc.sql("SELECT id FROM merchant_provider_accounts WHERE merchant_id = ? AND provider_code = ?")
                .param(1, merchant.id()).param(2, ALPHA).query(String.class).single();
        String body = json.write(event);
        return send("POST", "/v1/webhooks/providers/" + ALPHA + "/" + accountId,
                Map.of(MockPaymentProvider.SIGNATURE_HEADER, alpha().sign(clock.instant().getEpochSecond(), body)), body);
    }

    private MockPaymentProvider alpha() {
        return mockProviders.stream().filter(provider -> provider.code().equals(ALPHA)).findFirst().orElseThrow();
    }
}
