package com.payments.gateway.payment;

import com.payments.gateway.payment.application.TransferCreditService;
import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

/** Bank transfers to a virtual account per attempt (FR-VA1 to FR-VA3, ADR-038, LLD §21). */
class BankTransferIntegrationTest extends IntegrationTest {

    @Autowired
    private TransferCreditService transfers;

    private record Waiting(String paymentId, String attemptId, String account) {
    }

    @Test
    void anExactTransferPaysThePaymentAndClosesTheAccount() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 100_000, "automatic"), "id");
        Map<String, Object> request = Map.of("payment_method", Map.of("type", "bank_transfer"));

        Response confirmed = assertContract("POST", "/v1/payments/{payment_id}/confirm", request,
                post(merchant, "/v1/payments/" + paymentId + "/confirm", UUID.randomUUID().toString(), request));

        assertThat(confirmed.status()).as(confirmed.raw()).isEqualTo(200);
        assertThat(str(confirmed.body(), "status")).isEqualTo("requires_action");
        assertThat(str(confirmed.body(), "next_action.type")).isEqualTo("bank_transfer");
        assertThat(str(confirmed.body(), "next_action.bank_transfer.ifsc")).isEqualTo("MOCK0000001");
        assertThat(str(confirmed.body(), "next_action.bank_transfer.account_number")).matches("[0-9]{14}");
        assertThat(str(confirmed.body(), "next_action.bank_transfer.vpa")).endsWith("@mockbank");
        assertThat(str(confirmed.body(), "next_action.expires_at")).isEqualTo(str(confirmed.body(), "expires_at"));
        String account = str(confirmed.body(), "latest_attempt.provider_reference");

        credit(account, 100_000, "imps", true);

        Map<String, Object> payment = getPayment(merchant, paymentId);
        assertThat(str(payment, "status")).isEqualTo("succeeded");
        assertThat(num(payment, "amount_captured")).isEqualTo(100_000);
        Map<String, Object> credit = credits(merchant, paymentId).getFirst();
        assertThat(num(credit, "applied_amount")).isEqualTo(100_000);
        assertThat(num(credit, "returned_amount")).isZero();
        assertThat(str(credit, "mode")).isEqualTo("imps");
        assertThat(str(credit, "utr")).startsWith("UTR");
        assertThat(mock().psp().find(account, null).orElseThrow().closed()).as("closed once paid").isTrue();
        assertThat(ledgerBalances(merchant)).containsEntry("customer_funds", 0L).containsEntry("psp_receivable", 100_000L)
                .containsEntry("sales_clearing", 100_000L);
        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE type = 'PAYMENT_CAPTURED'")).isZero();
        assertThat(transfers.expire(paymentId)).as("funded before the expiry job got to it").isFalse();
        assertThat(count("SELECT count(*) FROM payment_attempts WHERE needs_review")).isZero();
    }

    @Test
    void aConfirmThatTimedOutFindsItsAccountOnTheStatusCheck() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 100_001, "automatic"), "id");
        assertThat(str(confirm(merchant, paymentId, Map.of("type", "bank_transfer")).body(), "status")).isEqualTo("processing");

        resolveStatuses(Duration.ofSeconds(15), 1);

        Map<String, Object> payment = getPayment(merchant, paymentId);
        assertThat(str(payment, "status")).isEqualTo("requires_action");
        assertThat(str(payment, "next_action.bank_transfer.ifsc")).isEqualTo("MOCK0000001");
    }

    @Test
    void creditsAddUpAndTheExcessGoesBack() {
        TestMerchant merchant = createMerchant(ALPHA);
        Waiting waiting = waiting(merchant, 100_000);

        credit(waiting.account(), 60_000, "neft", true);
        assertThat(str(getPayment(merchant, waiting.paymentId()), "status")).isEqualTo("requires_action");
        assertThat(mock().psp().find(waiting.account(), null).orElseThrow().closed()).as("open while short").isFalse();
        credit(waiting.account(), 50_000, "rtgs", true);

        Map<String, Object> payment = getPayment(merchant, waiting.paymentId());
        assertThat(str(payment, "status")).isEqualTo("succeeded");
        assertThat(num(payment, "amount_captured")).isEqualTo(100_000);
        assertThat(num(payment, "amount_refunded")).as("a return is not a refund of the payment").isZero();
        List<Map<String, Object>> credits = credits(merchant, waiting.paymentId());
        assertThat(credits).extracting(c -> c.get("applied_amount"), c -> c.get("returned_amount"))
                .containsExactly(tuple(60_000, 0), tuple(40_000, 10_000));
        Map<String, Object> excess = refunds(merchant, waiting.paymentId()).getFirst();
        assertThat(str(excess, "initiated_by")).isEqualTo("system_credit_return");
        assertThat(str(excess, "reason")).isEqualTo("excess");
        assertThat(str(excess, "credit_id")).isEqualTo(str(credits.get(1), "id"));
        assertThat(str(excess, "id")).isEqualTo(str(credits.get(1), "return_id"));
        assertThat(num(excess, "amount")).isEqualTo(10_000);
        assertThat(str(excess, "status")).isEqualTo("succeeded");
        assertThat(ledgerBalances(merchant)).containsEntry("customer_funds", 0L).containsEntry("psp_receivable", 100_000L)
                .containsEntry("sales_clearing", 100_000L).doesNotContainKey("refunds");
        assertThat(refund(merchant, waiting.paymentId(), 60_000).status()).isEqualTo(201);
        Response rest = refund(merchant, waiting.paymentId(), 40_000);
        assertThat(rest.status()).as("a return does not use up what can be refunded: " + rest.raw()).isEqualTo(201);
    }

    @Test
    void aPaymentStillShortAtExpiryIsExpiredAndItsCreditsGoBack() {
        TestMerchant merchant = createMerchant(ALPHA);
        Waiting waiting = waiting(merchant, 100_000);
        credit(waiting.account(), 40_000, "neft", true);

        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireDue();

        Map<String, Object> payment = getPayment(merchant, waiting.paymentId());
        assertThat(str(payment, "status")).isEqualTo("expired");
        assertThat(str(payment, "latest_attempt.status")).isEqualTo("failed");
        assertThat(str(payment, "latest_attempt.failure.code")).isEqualTo("transfer_not_completed");
        Map<String, Object> credit = credits(merchant, waiting.paymentId()).getFirst();
        assertThat(num(credit, "applied_amount")).isZero();
        assertThat(num(credit, "returned_amount")).isEqualTo(40_000);
        Map<String, Object> back = refunds(merchant, waiting.paymentId()).getFirst();
        assertThat(str(back, "reason")).isEqualTo("short_at_expiry");
        assertThat(str(back, "status")).isEqualTo("succeeded");
        assertThat(mock().psp().find(waiting.account(), null).orElseThrow().closed()).isTrue();
        assertThat(ledgerBalances(merchant)).containsEntry("customer_funds", 0L).containsEntry("psp_receivable", 0L)
                .doesNotContainKey("sales_clearing");
        assertThat(count("SELECT count(*) FROM payment_attempts WHERE id = ? AND next_status_check_at IS NULL",
                waiting.attemptId())).as("polling stopped").isEqualTo(1);
    }

    @Test
    void aMerchantMayAcceptAShortPaymentAtExpiryAndRefundIt() {
        TestMerchant merchant = createMerchant(ALPHA);
        settings(merchant, Map.of("bank_transfer_short_at_expiry", "accept"));
        Waiting waiting = waiting(merchant, 100_000);
        Waiting nothingArrived = waiting(merchant, 100_000);
        credit(waiting.account(), 90_000, "neft", true);

        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireDue();

        assertThat(str(getPayment(merchant, nothingArrived.paymentId()), "status")).isEqualTo("expired");
        Map<String, Object> payment = getPayment(merchant, waiting.paymentId());
        assertThat(str(payment, "status")).isEqualTo("succeeded");
        assertThat(num(payment, "amount_captured")).as("tax withheld at source").isEqualTo(90_000);
        assertThat(refunds(merchant, waiting.paymentId())).isEmpty();
        assertThat(ledgerBalances(merchant)).containsEntry("sales_clearing", 90_000L).containsEntry("customer_funds", 0L);
        Response refund = post(merchant, "/v1/payments/" + waiting.paymentId() + "/refunds", UUID.randomUUID().toString(),
                Map.of("amount", 90_000));
        assertThat(refund.status()).as(refund.raw()).isEqualTo(201);
        assertThat(str(refund.body(), "credit_id")).isEqualTo(str(credits(merchant, waiting.paymentId()).getFirst(), "id"));
        assertThat(num(getPayment(merchant, waiting.paymentId()), "amount_refunded")).isEqualTo(90_000);
    }

    @Test
    void exactOnlySendsAnyOtherCreditBackAtOnce() {
        TestMerchant merchant = createMerchant(ALPHA);
        settings(merchant, Map.of("bank_transfer_credits", "exact"));
        Waiting waiting = waiting(merchant, 100_000);
        Waiting neverExact = waiting(merchant, 100_000);
        credit(neverExact.account(), 99_000, "neft", true);

        credit(waiting.account(), 60_000, "neft", true);
        assertThat(str(getPayment(merchant, waiting.paymentId()), "status")).isEqualTo("requires_action");
        assertThat(str(refunds(merchant, waiting.paymentId()).getFirst(), "reason")).isEqualTo("inexact");
        credit(waiting.account(), 100_000, "neft", true);

        assertThat(str(getPayment(merchant, waiting.paymentId()), "status")).isEqualTo("succeeded");
        assertThat(credits(merchant, waiting.paymentId())).extracting(c -> c.get("applied_amount"))
                .containsExactly(0, 100_000);
        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireDue();
        assertThat(str(getPayment(merchant, neverExact.paymentId()), "status")).isEqualTo("expired");
        assertThat(refunds(merchant, neverExact.paymentId())).as("sent back once, when it arrived").hasSize(1);
        Response merchantView = admin("GET", "/admin/v1/merchants/" + merchant.id(), null);
        assertThat(str(merchantView.body(), "bank_transfer_credits")).isEqualTo("exact");
        assertThat(str(merchantView.body(), "bank_transfer_short_at_expiry")).isEqualTo("refund");
    }

    @Test
    void lostWebhooksAreRecoveredByStatusChecksAndRepeatedCreditsGoBack() {
        TestMerchant merchant = createMerchant(ALPHA);
        Waiting waiting = waiting(merchant, 100_000);
        resolveStatuses(Duration.ofSeconds(15), 1);
        String first = credit(waiting.account(), 100_000, "neft", false);
        assertThat(str(getPayment(merchant, waiting.paymentId()), "status")).isEqualTo("requires_action");

        resolveStatuses(Duration.ofSeconds(15), 1);
        assertThat(str(getPayment(merchant, waiting.paymentId()), "status")).as("the next check found it")
                .isEqualTo("succeeded");

        Response duplicate = pspWebhook(merchant, creditEvent(first, waiting.account(), waiting.attemptId(), 100_000));
        assertThat(duplicate.status()).isEqualTo(200);
        credit(waiting.account(), 100_000, "imps", true);

        List<Map<String, Object>> credits = credits(merchant, waiting.paymentId());
        assertThat(credits).as("the same credit is recorded once").hasSize(2);
        assertThat(num(credits.get(1), "returned_amount")).isEqualTo(100_000);
        Map<String, Object> back = refunds(merchant, waiting.paymentId()).getFirst();
        assertThat(str(back, "reason")).isEqualTo("late");
        assertThat(num(getPayment(merchant, waiting.paymentId()), "amount_captured")).isEqualTo(100_000);
    }

    @Test
    void aCreditTheGatewayCannotPlaceIsQueuedForReviewNotSentBack() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> event = creditEvent("mock_alpha_cr_unknown", "mock_alpha_va_unknown", null, 5_000);

        assertThat(pspWebhook(merchant, event).status()).isEqualTo(200);

        List<Map<String, Object>> reviews = list(admin("GET", "/admin/v1/reviews?kind=credit", null).body(), "data");
        assertThat(reviews).hasSize(1);
        assertThat(reviews.getFirst()).containsEntry("status", "unmatched").containsEntry("amount", 5_000)
                .containsEntry("reasons", List.of("unmatched_credit"));
        assertThat(count("SELECT count(*) FROM refunds")).isZero();
        assertThat(ledgerBalances(merchant)).containsEntry("customer_funds", 5_000L).containsEntry("psp_receivable", 5_000L);
        Response resolved = admin("POST", "/admin/v1/reviews/credits/" + reviews.getFirst().get("id") + "/resolve",
                Map.of("note", "returned from the PSP dashboard"));
        assertThat(resolved.status()).as(resolved.raw()).isEqualTo(200);
        assertThat(resolved.body()).containsEntry("open", false);

        String body = json.write(creditEvent("mock_alpha_cr_platform", "mock_alpha_va_unknown", null, 7_000));
        Response platform = send("POST", "/v1/webhooks/providers/" + ALPHA,
                Map.of(MockPaymentProvider.SIGNATURE_HEADER, mock().sign(clock.instant().getEpochSecond(), body)), body);
        assertThat(platform.status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM transfer_credits")).as("no merchant to record it under").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM provider_webhook_events WHERE status = 'IGNORED'")).isEqualTo(1);
    }

    @Test
    void aCreditIsPlacedByTheAttemptIdWhenTheAccountIsMissingAndUnknownModesAreLeftOut() {
        TestMerchant merchant = createMerchant(ALPHA);
        Waiting waiting = waiting(merchant, 100_000);
        Map<String, Object> event = creditEvent("mock_alpha_cr_orphan", null, waiting.attemptId(), 100_000);
        event.put("transfer_mode", "IFT");

        assertThat(pspWebhook(merchant, event).status()).isEqualTo(200);

        assertThat(str(getPayment(merchant, waiting.paymentId()), "status")).isEqualTo("succeeded");
        assertThat(credits(merchant, waiting.paymentId()).getFirst()).doesNotContainKey("mode");
    }

    @Test
    void aCreditReportedOnAnotherMerchantsAccountIsIgnored() {
        TestMerchant merchant = createMerchant(ALPHA);
        TestMerchant other = createMerchant(ALPHA);
        Waiting waiting = waiting(merchant, 100_000);

        Response foreign = pspWebhook(other, creditEvent("mock_alpha_cr_foreign", waiting.account(), waiting.attemptId(),
                100_000));

        assertThat(foreign.status()).isEqualTo(200);
        assertThat(str(getPayment(merchant, waiting.paymentId()), "status")).isEqualTo("requires_action");
        assertThat(count("SELECT count(*) FROM transfer_credits")).isZero();
    }

    @Test
    void otherMethodsExpireAsBefore() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 100_000, "automatic"), "id");
        confirm(merchant, paymentId, upi("intent"));

        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireDue();

        Map<String, Object> payment = getPayment(merchant, paymentId);
        assertThat(str(payment, "status")).isEqualTo("expired");
        assertThat(str(payment, "latest_attempt.status")).as("the PSP decides an unpaid UPI attempt")
                .isEqualTo("requires_action");
    }

    @Test
    void aReturnThePspRefusesIsQueuedForReview() {
        TestMerchant merchant = createMerchant(ALPHA);
        Waiting waiting = waiting(merchant, 100_000);

        credit(waiting.account(), 100_009, "neft", true);

        Map<String, Object> back = refunds(merchant, waiting.paymentId()).getFirst();
        assertThat(num(back, "amount")).isEqualTo(9);
        assertThat(str(back, "status")).isEqualTo("failed");
        List<Map<String, Object>> reviews = list(admin("GET", "/admin/v1/reviews?kind=refund", null).body(), "data");
        assertThat(reviews).extracting(review -> review.get("reasons")).containsExactly(List.of("credit_return_failed"));
        assertThat(ledgerBalances(merchant)).as("the money is still held").containsEntry("customer_funds", 9L);
    }

    @Test
    void aMerchantRefundGoesBackAgainstOneCredit() {
        TestMerchant merchant = createMerchant(ALPHA);
        Waiting waiting = waiting(merchant, 100_000);
        credit(waiting.account(), 60_000, "neft", true);
        credit(waiting.account(), 40_000, "neft", true);
        List<Map<String, Object>> credits = credits(merchant, waiting.paymentId());

        Response first = refund(merchant, waiting.paymentId(), 50_000);
        Response tooLarge = refund(merchant, waiting.paymentId(), 50_000);
        Response second = refund(merchant, waiting.paymentId(), 40_000);

        assertThat(str(first.body(), "credit_id")).isEqualTo(str(credits.get(0), "id"));
        assertThat(tooLarge.status()).isEqualTo(422);
        assertThat(str(tooLarge.body(), "code")).isEqualTo("amount_exceeds_refundable");
        assertThat(str(tooLarge.body(), "detail")).contains("at most 40000 can be refunded in one refund");
        assertThat(str(second.body(), "credit_id")).isEqualTo(str(credits.get(1), "id"));
        assertThat(num(getPayment(merchant, waiting.paymentId()), "amount_refunded")).isEqualTo(90_000);
        assertThat(ledgerBalances(merchant)).containsEntry("refunds", 90_000L).containsEntry("psp_receivable", 10_000L);
    }

    @Test
    void creditsAndTheirReturnsReconcileAgainstTheSettlementReport() {
        TestMerchant merchant = createMerchant(ALPHA);
        Waiting waiting = waiting(merchant, 100_000);
        credit(waiting.account(), 60_000, "neft", true);
        credit(waiting.account(), 50_000, "neft", true);

        Response run = reconcileAroundNow(merchant);

        assertThat(run.status()).as(run.raw()).isEqualTo(201);
        assertThat(num(run.body(), "lines_matched")).as("two credits and the return of the excess").isEqualTo(3);
        assertThat(num(run.body(), "exceptions_opened")).isZero();
        assertThat(num(run.body(), "gross_amount")).isEqualTo(110_000);

        TestMerchant other = createMerchant(ALPHA);
        Waiting dropped = waiting(other, 50_000);
        String lost = credit(dropped.account(), 50_000, "neft", true);
        send("POST", "/simulator/" + ALPHA + "/report-anomalies", Map.of(), Map.of("type", "drop", "provider_reference", lost));
        Map<String, Object> exception = list(reconcileAroundNow(other).body(), "exceptions").getFirst();
        assertThat(str(exception, "type")).isEqualTo("missing_at_provider");
        assertThat(str(exception, "entity_id")).isEqualTo(str(credits(other, dropped.paymentId()).getFirst(), "id"));
        assertThat(num(exception, "expected_amount")).isEqualTo(50_000);
    }

    @Test
    void bankTransfersCaptureAutomaticallyOnly() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 100_000, "manual"), "id");

        Response refused = confirm(merchant, paymentId, Map.of("type", "bank_transfer"));

        assertThat(refused.status()).isEqualTo(422);
        assertThat(str(refused.body(), "code")).isEqualTo("unsupported_payment_method");
    }

    @Test
    void theDatabaseKeepsCreditsConsistent() {
        TestMerchant merchant = createMerchant(ALPHA);
        Waiting waiting = waiting(merchant, 100_000);
        credit(waiting.account(), 110_000, "neft", true);
        String creditId = str(credits(merchant, waiting.paymentId()).getFirst(), "id");
        String update = "UPDATE transfer_credits SET %s WHERE id = ?";

        for (Map.Entry<String, String> broken : Map.of(
                "applied_amount = amount", "ck_transfer_credit_allocation",
                "return_refund_id = NULL", "ck_transfer_credit_return",
                "payment_id = NULL", "ck_transfer_credit_placed",
                "attempt_id = NULL, payment_id = NULL", "ck_transfer_credit_unmatched",
                "mode = 'CHEQUE'", "transfer_credits_mode_check").entrySet()) {
            assertThat(catchThrowable(() -> jdbc.sql(update.formatted(broken.getKey())).param(1, creditId).update()))
                    .as(broken.getKey()).hasStackTraceContaining(broken.getValue());
        }
        assertThat(catchThrowable(() -> jdbc.sql("UPDATE refunds SET credit_id = NULL").update()))
                .hasStackTraceContaining("ck_refunds_credit_return");
        assertThat(catchThrowable(() -> jdbc.sql("""
                INSERT INTO transfer_credits (id, merchant_id, provider_code, provider_reference, amount, currency,
                                              received_at, applied_amount, returned_amount, version, created_at, updated_at)
                SELECT 'trc_copy', merchant_id, provider_code, provider_reference, amount, currency, received_at, 0, 0, 0,
                       created_at, updated_at FROM transfer_credits WHERE id = ?
                """).param(1, creditId).update())).as("a credit is recorded once").hasStackTraceContaining(
                "ux_transfer_credits_provider_ref");
    }

    private Waiting waiting(TestMerchant merchant, long amount) {
        String paymentId = str(createPayment(merchant, amount, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, Map.of("type", "bank_transfer"));
        assertThat(confirmed.status()).as(confirmed.raw()).isEqualTo(200);
        return new Waiting(paymentId, str(confirmed.body(), "latest_attempt.id"),
                str(confirmed.body(), "latest_attempt.provider_reference"));
    }

    /** Credits the account through the simulator; returns the credit's PSP reference. */
    private String credit(String account, long amount, String mode, boolean webhook) {
        Response credited = send("POST", "/simulator/" + ALPHA + "/collections/" + account + "/credits", Map.of(),
                Map.of("amount", amount, "mode", mode, "webhook", webhook));
        assertThat(credited.status()).as(credited.raw()).isEqualTo(200);
        return str(credited.body(), "credit_reference");
    }

    private List<Map<String, Object>> credits(TestMerchant merchant, String paymentId) {
        Response listed = assertContract("GET", "/v1/payments/{payment_id}/credits", null,
                get(merchant, "/v1/payments/" + paymentId + "/credits"));
        assertThat(listed.status()).isEqualTo(200);
        return list(listed.body(), "data");
    }

    private List<Map<String, Object>> refunds(TestMerchant merchant, String paymentId) {
        Response listed = assertContract("GET", "/v1/payments/{payment_id}/refunds", null,
                get(merchant, "/v1/payments/" + paymentId + "/refunds"));
        return list(listed.body(), "data");
    }

    private Response refund(TestMerchant merchant, String paymentId, long amount) {
        return post(merchant, "/v1/payments/" + paymentId + "/refunds", UUID.randomUUID().toString(), Map.of("amount", amount));
    }

    private void settings(TestMerchant merchant, Map<String, Object> update) {
        Response patched = admin("PATCH", "/admin/v1/merchants/" + merchant.id(), update);
        assertThat(patched.status()).as(patched.raw()).isEqualTo(200);
    }

    private static Map<String, Object> creditEvent(String creditReference, String account, String attemptId, long amount) {
        Map<String, Object> event = new HashMap<>();
        event.put("event_id", "evt_" + UUID.randomUUID());
        event.put("type", "collection.credited");
        event.put("provider_reference", creditReference);
        event.put("merchant_reference", attemptId);
        event.put("status", "captured");
        event.put("amount", amount);
        event.put("currency", "INR");
        event.put("payment_reference", account);
        event.put("transfer_mode", "IMPS");
        event.put("utr", "UTR000000000001");
        return event;
    }

    private Response reconcileAroundNow(TestMerchant merchant) {
        return admin("POST", "/admin/v1/reconciliation/runs", Map.of(
                "merchant_id", merchant.id(),
                "provider", ALPHA,
                "from", clock.instant().minus(Duration.ofHours(1)).toString(),
                "to", clock.instant().plus(Duration.ofHours(1)).toString()));
    }

    /** Posts a webhook as the mock PSP would to the merchant's account endpoint (platform secret). */
    private Response pspWebhook(TestMerchant merchant, Map<String, Object> event) {
        String accountId = jdbc.sql("SELECT id FROM merchant_provider_accounts WHERE merchant_id = ?").param(1, merchant.id())
                .query(String.class).single();
        String body = json.write(event);
        return send("POST", "/v1/webhooks/providers/" + ALPHA + "/" + accountId,
                Map.of(MockPaymentProvider.SIGNATURE_HEADER, mock().sign(clock.instant().getEpochSecond(), body)), body);
    }

    private MockPaymentProvider mock() {
        return mockProviders.stream().filter(p -> p.code().equals(ALPHA)).findFirst().orElseThrow();
    }
}
