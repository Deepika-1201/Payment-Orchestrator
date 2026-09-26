package com.payments.gateway.payment;

import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.support.FakeMerchantEndpoint;
import com.payments.gateway.support.IntegrationTest;
import com.payments.gateway.support.OpenApiContract;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** Chargebacks and UPI disputes (FR-D1, ADR-018). */
class DisputeIntegrationTest extends IntegrationTest {

    @Test
    void openDisputeWithholdsFundsBlocksRefundingThemAndAWinReturnsThem() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);
            Map<String, Object> payment = payAndSucceed(merchant, 100_000);
            String paymentId = str(payment, "id");

            String pspDisputeId = openDispute(payment, Map.of("amount", 60_000, "reason", "product_not_received"));

            List<Map<String, Object>> disputes = list(assertContract("GET", "/v1/payments/{payment_id}/disputes", null,
                    get(merchant, "/v1/payments/" + paymentId + "/disputes")).body(), "data");
            assertThat(disputes).hasSize(1);
            Map<String, Object> dispute = disputes.getFirst();
            assertThat(str(dispute, "status")).isEqualTo("open");
            assertThat(num(dispute, "amount")).isEqualTo(60_000);
            assertThat(str(dispute, "reason")).isEqualTo("product_not_received");
            assertThat(str(dispute, "provider_reference")).isEqualTo(pspDisputeId);
            assertThat(str(dispute, "attempt_id")).isEqualTo(str(payment, "latest_attempt.id"));
            assertThat(dispute).containsKey("respond_by");
            String disputeId = str(dispute, "id");
            assertContract("GET", "/v1/disputes/{dispute_id}", null, get(merchant, "/v1/disputes/" + disputeId));
            assertThat(ledgerBalances(merchant)).containsEntry("chargebacks", 60_000L).containsEntry("psp_receivable", 40_000L);

            Response tooMuch = refund(merchant, paymentId, 50_000);
            assertThat(tooMuch.status()).isEqualTo(422);
            assertThat(str(tooMuch.body(), "detail")).contains("held by open or lost disputes");
            assertThat(refund(merchant, paymentId, 40_000).status()).as("the undisputed part").isEqualTo(201);

            updateDispute(pspDisputeId, "under_review");
            updateDispute(pspDisputeId, "won");

            assertThat(str(get(merchant, "/v1/disputes/" + disputeId).body(), "status")).isEqualTo("won");
            assertThat(ledgerBalances(merchant)).containsEntry("chargebacks", 0L);
            assertThat(refund(merchant, paymentId, 60_000).status()).as("a won dispute releases the amount").isEqualTo(201);
            deliveryWorker.deliverDue();
            List<String> types = endpoint.received().stream()
                    .peek(delivery -> OpenApiContract.get().assertSchema("Event", delivery.body()))
                    .map(delivery -> str(json.read(delivery.body(), new TypeReference<Map<String, Object>>() { }), "type"))
                    .filter(type -> type.startsWith("dispute."))
                    .toList();
            assertThat(types).as("deliveries may arrive out of order").containsExactlyInAnyOrder("dispute.created",
                    "dispute.updated", "dispute.won");
        }
    }

    @Test
    void lostDisputeKeepsTheFundsAndALaterContradictingWinGoesToReview() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 50_000);
        String pspDisputeId = openDispute(payment, Map.of());

        updateDispute(pspDisputeId, "lost");
        updateDispute(pspDisputeId, "won");

        Map<String, Object> dispute = list(get(merchant, "/v1/payments/" + str(payment, "id") + "/disputes").body(), "data").getFirst();
        assertThat(str(dispute, "status")).as("final statuses never change").isEqualTo("lost");
        assertThat(num(dispute, "amount")).isEqualTo(50_000);
        assertThat(ledgerBalances(merchant)).containsEntry("chargebacks", 50_000L);
        assertThat(refund(merchant, str(payment, "id"), 1_000).status()).isEqualTo(422);
        List<Map<String, Object>> reviews = list(admin("GET", "/admin/v1/reviews?kind=dispute&merchant_id=" + merchant.id(), null).body(), "data");
        assertThat(reviews).extracting(r -> r.get("id")).containsExactly(dispute.get("id"));
        assertThat(reviews.getFirst().get("reasons")).isEqualTo(List.of("provider_conflict"));
        Response resolved = admin("POST", "/admin/v1/reviews/disputes/" + dispute.get("id") + "/resolve",
                Map.of("note", "PSP confirms the loss; its won notice was sent in error"));
        assertThat(resolved.status()).as(resolved.raw()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM disputes WHERE needs_review")).isZero();
    }

    @Test
    void disputeLargerThanWhatIsLeftAfterRefundsIsRecordedAndQueued() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 40_000);
        assertThat(refund(merchant, str(payment, "id"), 40_000).status()).isEqualTo(201);

        openDispute(payment, Map.of("reason", "credit_not_processed"));

        Map<String, Object> review = list(admin("GET", "/admin/v1/reviews?kind=dispute", null).body(), "data").getFirst();
        assertThat(review.get("reasons")).isEqualTo(List.of("amount_exceeds_net_captured"));
        assertThat(ledgerBalances(merchant)).as("the PSP still withholds it").containsEntry("chargebacks", 40_000L);
    }

    @Test
    void chargebacksSeenOnlyInSettlementReportsAreRecordedAndTheSettlementsNet() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 100_000);
        String pspDisputeId = openDispute(payment, Map.of("amount", 30_000, "send_webhook", false));
        assertThat(list(get(merchant, "/v1/payments/" + str(payment, "id") + "/disputes").body(), "data")).isEmpty();

        Response run = reconcileAroundNow(merchant);

        assertThat(run.status()).as(run.raw()).isEqualTo(201);
        assertThat(num(run.body(), "lines_total")).isEqualTo(2);
        assertThat(num(run.body(), "lines_auto_healed")).isEqualTo(1);
        assertThat(num(run.body(), "exceptions_opened")).isZero();
        assertThat(num(run.body(), "chargeback_amount")).isEqualTo(30_000);
        assertThat(num(run.body(), "settled_amount")).isEqualTo(68_000);
        Map<String, Object> dispute = list(get(merchant, "/v1/payments/" + str(payment, "id") + "/disputes").body(), "data").getFirst();
        assertThat(str(dispute, "reason")).isEqualTo("reported_in_settlement");
        assertThat(ledgerBalances(merchant)).containsEntry("psp_receivable", 0L).containsEntry("chargebacks", 30_000L);

        clock.advance(Duration.ofDays(1));
        updateDispute(pspDisputeId, "won", false);
        Response nextDay = reconcileAroundNow(merchant);

        assertThat(num(nextDay.body(), "lines_auto_healed")).as("reversal line heals the dispute to won").isEqualTo(1);
        assertThat(num(nextDay.body(), "exceptions_opened")).isZero();
        assertThat(num(nextDay.body(), "chargeback_amount")).isEqualTo(-30_000);
        assertThat(str(get(merchant, "/v1/disputes/" + str(dispute, "id")).body(), "status")).isEqualTo("won");
        assertThat(ledgerBalances(merchant)).containsEntry("psp_receivable", 0L).containsEntry("chargebacks", 0L);
    }

    @Test
    void disputeWebhookFromAnotherMerchantsAccountCannotTouchThePayment() {
        TestMerchant victim = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(victim, 20_000);
        TestMerchant attacker = createMerchant(ALPHA);
        String attackerAccount = jdbc.sql("SELECT id FROM merchant_provider_accounts WHERE merchant_id = ?")
                .param(1, attacker.id()).query(String.class).single();
        Map<String, Object> event = new HashMap<>();
        event.put("event_id", "evt_forged_dispute");
        event.put("type", "dispute.updated");
        event.put("provider_reference", "mock_dsp_forged");
        event.put("merchant_reference", str(payment, "latest_attempt.id"));
        event.put("payment_reference", str(payment, "latest_attempt.provider_reference"));
        event.put("status", "lost");
        event.put("amount", 20_000);
        event.put("currency", "INR");
        String body = json.write(event);

        Response response = send("POST", "/v1/webhooks/providers/" + ALPHA + "/" + attackerAccount,
                Map.of(MockPaymentProvider.SIGNATURE_HEADER, alpha().sign(clock.instant().getEpochSecond(), body)), body);

        assertThat(response.status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM provider_webhook_events WHERE provider_event_id = 'evt_forged_dispute'")
                .query(String.class).single()).isEqualTo("IGNORED");
        assertThat(count("SELECT count(*) FROM disputes")).isZero();
        assertThat(ledgerBalances(victim)).doesNotContainKey("chargebacks");
    }

    private String openDispute(Map<String, Object> payment, Map<String, Object> request) {
        Response opened = send("POST", "/simulator/" + ALPHA + "/payments/" + str(payment, "latest_attempt.provider_reference")
                + "/dispute", Map.of(), request);
        assertThat(opened.status()).as(opened.raw()).isEqualTo(200);
        return str(opened.body(), "dispute_id");
    }

    private void updateDispute(String pspDisputeId, String status) {
        updateDispute(pspDisputeId, status, true);
    }

    private void updateDispute(String pspDisputeId, String status, boolean sendWebhook) {
        Response updated = send("POST", "/simulator/" + ALPHA + "/disputes/" + pspDisputeId + "/status", Map.of(),
                Map.of("status", status, "send_webhook", sendWebhook));
        assertThat(updated.status()).as(updated.raw()).isEqualTo(200);
    }

    private Response refund(TestMerchant merchant, String paymentId, long amount) {
        return post(merchant, "/v1/payments/" + paymentId + "/refunds", UUID.randomUUID().toString(), Map.of("amount", amount));
    }

    private Response reconcileAroundNow(TestMerchant merchant) {
        return admin("POST", "/admin/v1/reconciliation/runs", Map.of(
                "merchant_id", merchant.id(),
                "provider", ALPHA,
                "from", clock.instant().minus(Duration.ofHours(1)).toString(),
                "to", clock.instant().plus(Duration.ofHours(1)).toString()));
    }

    private MockPaymentProvider alpha() {
        return mockProviders.stream().filter(provider -> provider.code().equals(ALPHA)).findFirst().orElseThrow();
    }
}
