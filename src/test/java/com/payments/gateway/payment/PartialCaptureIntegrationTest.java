package com.payments.gateway.payment;

import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.provider.mock.MockPsp.Txn;
import com.payments.gateway.provider.mock.MockPsp.TxnState;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.shared.model.Money;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/** Partial capture (FR-P10, ADR-036, LLD §19). */
class PartialCaptureIntegrationTest extends IntegrationTest {

    private record Authorized(String paymentId, String attemptId, String reference) {
    }

    @Test
    void partialCaptureReleasesTheRestAndRefundsTheLedgerAndReconciliationUseTheCapturedAmount() {
        TestMerchant merchant = createMerchant(ALPHA);
        Authorized authorized = authorize(merchant, ALPHA, 100_000);
        Map<String, Object> request = Map.of("amount", 60_000);

        Response captured = assertContract("POST", "/v1/payments/{payment_id}/capture", request, post(merchant,
                "/v1/payments/" + authorized.paymentId() + "/capture", UUID.randomUUID().toString(), request));

        assertThat(captured.status()).as(captured.raw()).isEqualTo(200);
        assertThat(str(captured.body(), "status")).isEqualTo("succeeded");
        assertThat(num(captured.body(), "amount")).isEqualTo(100_000);
        assertThat(num(captured.body(), "amount_captured")).isEqualTo(60_000);
        Txn txn = transaction(ALPHA, authorized.reference());
        assertThat(txn.state()).isEqualTo(TxnState.CAPTURED);
        assertThat(txn.capturedAmount()).as("the PSP released the other 40,000").isEqualTo(Money.of(60_000, "INR"));
        assertThat(count("SELECT count(*) FROM merchant_events WHERE type = 'payment.succeeded'"
                + " AND payload LIKE '%\"amount_captured\":60000%'")).isEqualTo(1);
        assertThat(ledgerBalances(merchant)).containsEntry("psp_receivable", 60_000L).containsEntry("sales_clearing", 60_000L);

        Response again = capture(merchant, authorized.paymentId(), 40_000);
        assertThat(again.status()).as("one capture per payment").isEqualTo(409);
        Response overRefund = refund(merchant, authorized.paymentId(), 60_001);
        assertThat(overRefund.status()).isEqualTo(422);
        assertThat(str(overRefund.body(), "code")).isEqualTo("amount_exceeds_refundable");
        assertThat(refund(merchant, authorized.paymentId(), 20_000).status()).isEqualTo(201);

        Response run = reconcileAroundNow(merchant);
        assertThat(run.status()).as(run.raw()).isEqualTo(201);
        assertThat(num(run.body(), "lines_matched")).isEqualTo(2);
        assertThat(num(run.body(), "exceptions_opened")).isZero();
        assertThat(num(run.body(), "gross_amount")).isEqualTo(60_000);
    }

    @Test
    void captureMissingFromTheReportIsExpectedAtTheCapturedAmount() {
        TestMerchant merchant = createMerchant(ALPHA);
        Authorized authorized = authorize(merchant, ALPHA, 100_000);
        assertThat(num(capture(merchant, authorized.paymentId(), 45_000).body(), "amount_captured")).isEqualTo(45_000);
        Response dropped = send("POST", "/simulator/" + ALPHA + "/report-anomalies", Map.of(),
                Map.of("type", "drop", "provider_reference", authorized.reference()));
        assertThat(dropped.status()).isEqualTo(200);

        Map<String, Object> exception = list(reconcileAroundNow(merchant).body(), "exceptions").getFirst();

        assertThat(str(exception, "type")).isEqualTo("missing_at_provider");
        assertThat(str(exception, "entity_id")).isEqualTo(authorized.attemptId());
        assertThat(num(exception, "expected_amount")).isEqualTo(45_000);
    }

    @Test
    void captureAboveTheAuthorizationIsRefusedWithoutCallingThePsp() {
        TestMerchant merchant = createMerchant(ALPHA);
        Authorized authorized = authorize(merchant, ALPHA, 100_000);

        Response above = capture(merchant, authorized.paymentId(), 100_001);
        Response zero = capture(merchant, authorized.paymentId(), 0);

        assertThat(above.status()).isEqualTo(422);
        assertThat(str(above.body(), "code")).isEqualTo("capture_amount_mismatch");
        assertThat(zero.status()).isEqualTo(400);
        assertThat(str(zero.body(), "code")).isEqualTo("validation_error");
        assertThat(str(getPayment(merchant, authorized.paymentId()), "status")).isEqualTo("authorized");
        assertThat(transaction(ALPHA, authorized.reference()).state()).isEqualTo(TxnState.AUTHORIZED);
        assertThat(count("SELECT count(*) FROM payment_attempts WHERE capture_amount IS NOT NULL")).isZero();
    }

    @Test
    void partialCaptureOnAPspThatCannotReleaseTheRestIsRefused() {
        TestMerchant merchant = createMerchant(BETA);
        Authorized authorized = authorize(merchant, BETA, 100_000);

        Response refused = capture(merchant, authorized.paymentId(), 50_000);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(str(refused.body(), "code")).isEqualTo("unsupported_payment_method");
        assertThat(str(refused.body(), "detail")).isEqualTo(
                "Provider " + BETA + " cannot capture less than the authorized amount 100000");
        assertThat(transaction(BETA, authorized.reference()).state()).isEqualTo(TxnState.AUTHORIZED);
        Response full = capture(merchant, authorized.paymentId(), 100_000);
        assertThat(str(full.body(), "status")).isEqualTo("succeeded");
        assertThat(num(full.body(), "amount_captured")).isEqualTo(100_000);
    }

    @Test
    void captureInterruptedByAnOutageIsRetriedForTheSameAmount() {
        TestMerchant merchant = createMerchant(ALPHA);
        Authorized authorized = authorize(merchant, ALPHA, 100_000);
        mock(ALPHA).psp().setAvailable(false);

        Response pending = capture(merchant, authorized.paymentId(), 25_000);

        assertThat(str(pending.body(), "status")).isEqualTo("processing");
        assertThat(count("SELECT capture_amount FROM payment_attempts WHERE id = ?", authorized.attemptId()))
                .isEqualTo(25_000);
        mock(ALPHA).psp().setAvailable(true);
        resolveStatuses(Duration.ofSeconds(15), 2);
        Map<String, Object> payment = getPayment(merchant, authorized.paymentId());
        assertThat(str(payment, "status")).isEqualTo("succeeded");
        assertThat(num(payment, "amount_captured")).isEqualTo(25_000);
        assertThat(transaction(ALPHA, authorized.reference()).capturedAmount()).isEqualTo(Money.of(25_000, "INR"));
    }

    @Test
    void disputeAboveWhatIsLeftOfThePartialCaptureIsQueuedForReview() {
        TestMerchant merchant = createMerchant(ALPHA);
        Authorized authorized = authorize(merchant, ALPHA, 100_000);
        capture(merchant, authorized.paymentId(), 60_000);
        assertThat(refund(merchant, authorized.paymentId(), 30_000).status()).isEqualTo(201);
        String disputePath = "/simulator/" + ALPHA + "/payments/" + authorized.reference() + "/dispute";
        assertThat(send("POST", disputePath, Map.of(), Map.of("amount", 60_001)).status())
                .as("a chargeback cannot exceed the captured amount").isEqualTo(400);

        Response opened = send("POST", disputePath, Map.of(), Map.of());

        assertThat(opened.status()).as(opened.raw()).isEqualTo(200);
        Map<String, Object> dispute = list(get(merchant, "/v1/payments/" + authorized.paymentId() + "/disputes").body(),
                "data").getFirst();
        assertThat(num(dispute, "amount")).as("the whole captured amount").isEqualTo(60_000);
        List<Map<String, Object>> reviews = list(admin("GET", "/admin/v1/reviews?kind=dispute", null).body(), "data");
        assertThat(reviews).extracting(review -> review.get("reasons")).containsExactly(List.of("amount_exceeds_net_captured"));
    }

    @Test
    void duplicateSuccessOfAPartialCaptureRefundsWhatItCaptured() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 100_000, "manual"), "id");
        Response first = confirm(merchant, paymentId, card());
        simulate(ALPHA, str(first.body(), "latest_attempt.provider_reference"), "failure", false);
        Response second = confirm(merchant, paymentId, card());
        simulate(ALPHA, str(second.body(), "latest_attempt.provider_reference"), "success", false);
        mock(ALPHA).psp().setAvailable(false);
        assertThat(str(capture(merchant, paymentId, 60_000).body(), "status")).isEqualTo("processing");
        mock(ALPHA).psp().setAvailable(true);

        Response late = pspWebhook(merchant, Map.of("event_id", "evt_late_capture", "type", "payment.updated",
                "provider_reference", str(first.body(), "latest_attempt.provider_reference"),
                "merchant_reference", str(first.body(), "latest_attempt.id"),
                "status", "captured", "amount", 100_000, "currency", "INR"));
        assertThat(late.status()).isEqualTo(200);
        assertThat(num(getPayment(merchant, paymentId), "amount_captured")).as("the first attempt won").isEqualTo(100_000);
        resolveStatuses(Duration.ofSeconds(15), 2);

        List<Map<String, Object>> refunds = list(get(merchant, "/v1/payments/" + paymentId + "/refunds").body(), "data");
        assertThat(refunds).hasSize(1);
        assertThat(str(refunds.getFirst(), "initiated_by")).isEqualTo("system_duplicate_success");
        assertThat(num(refunds.getFirst(), "amount")).as("what the second attempt captured").isEqualTo(60_000);
        assertThat(str(refunds.getFirst(), "status")).isEqualTo("succeeded");
    }

    @Test
    void mockPspRefusesWhatTheGatewayNeverSends() {
        TestMerchant alphaMerchant = createMerchant(ALPHA);
        TestMerchant betaMerchant = createMerchant(BETA);
        Authorized alpha = authorize(alphaMerchant, ALPHA, 100_000);
        Authorized beta = authorize(betaMerchant, BETA, 100_000);

        ProviderPaymentResult above = providerClient.capture(alphaMerchant.id(), ALPHA,
                new CaptureRequest(alpha.attemptId(), alpha.reference(), Money.of(100_001, "INR")));
        ProviderPaymentResult partial = providerClient.capture(betaMerchant.id(), BETA,
                new CaptureRequest(beta.attemptId(), beta.reference(), Money.of(50_000, "INR")));

        assertThat(above.failure().code()).isEqualTo("amount_exceeds_authorization");
        assertThat(partial.failure().code()).isEqualTo("partial_capture_not_supported");
        assertThat(transaction(ALPHA, alpha.reference()).state()).isEqualTo(TxnState.AUTHORIZED);
        assertThat(transaction(BETA, beta.reference()).state()).isEqualTo(TxnState.AUTHORIZED);
        capture(alphaMerchant, alpha.paymentId(), 60_000);
        ProviderRefundResult refund = providerClient.refund(alphaMerchant.id(), ALPHA,
                new RefundRequest("rfnd_direct", alpha.attemptId(), alpha.reference(), Money.of(60_001, "INR"), null));
        assertThat(refund.failure().code()).as("refunds are limited by the captured amount").isEqualTo("refund_not_allowed");
    }

    @Test
    void theDatabaseKeepsTheCaptureAmountWithinTheAuthorization() {
        TestMerchant merchant = createMerchant(ALPHA);
        String captured = str(payAndSucceed(merchant, 50_000), "latest_attempt.id");
        Authorized authorized = authorize(merchant, ALPHA, 100_000);
        String update = "UPDATE payment_attempts SET capture_amount = %s WHERE id = ?";

        assertThat(count("SELECT capture_amount FROM payment_attempts WHERE id = ?", captured)).isEqualTo(50_000);
        for (String invalid : List.of("amount + 1", "0", "NULL")) {
            assertThat(catchThrowable(() -> jdbc.sql(update.formatted(invalid)).param(1, captured).update()))
                    .as(invalid).hasStackTraceContaining("ck_attempts_capture_amount");
        }
        assertThat(catchThrowable(() -> jdbc.sql(update.formatted("1")).param(1, authorized.attemptId()).update()))
                .as("an attempt that is not capturing has none").hasStackTraceContaining("ck_attempts_capture_amount");
        assertThat(jdbc.sql(update.formatted("1")).param(1, captured).update()).isEqualTo(1);
    }

    private Authorized authorize(TestMerchant merchant, String provider, long amount) {
        String paymentId = str(createPayment(merchant, amount, "manual"), "id");
        Response confirmed = confirm(merchant, paymentId, card());
        assertThat(str(confirmed.body(), "latest_attempt.provider")).isEqualTo(provider);
        String reference = str(confirmed.body(), "latest_attempt.provider_reference");
        simulate(provider, reference, "success", false);
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("authorized");
        return new Authorized(paymentId, str(confirmed.body(), "latest_attempt.id"), reference);
    }

    private Response capture(TestMerchant merchant, String paymentId, long amount) {
        return post(merchant, "/v1/payments/" + paymentId + "/capture", UUID.randomUUID().toString(),
                Map.of("amount", amount));
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

    private Txn transaction(String provider, String reference) {
        return mock(provider).psp().find(reference, null).orElseThrow();
    }

    /** Posts a webhook as the mock PSP would to the merchant's account endpoint (platform secret). */
    private Response pspWebhook(TestMerchant merchant, Map<String, Object> event) {
        String account = jdbc.sql("SELECT id FROM merchant_provider_accounts WHERE merchant_id = ?").param(1, merchant.id())
                .query(String.class).single();
        String body = json.write(event);
        return send("POST", "/v1/webhooks/providers/" + ALPHA + "/" + account,
                Map.of(MockPaymentProvider.SIGNATURE_HEADER, mock(ALPHA).sign(clock.instant().getEpochSecond(), body)), body);
    }

    private MockPaymentProvider mock(String provider) {
        return mockProviders.stream().filter(p -> p.code().equals(provider)).findFirst().orElseThrow();
    }
}
