package com.payments.gateway.reconciliation;

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

class ReconciliationIntegrationTest extends IntegrationTest {

    private Response reconcile(TestMerchant merchant, String provider) {
        return admin("POST", "/admin/v1/reconciliation/runs", Map.of(
                "merchant_id", merchant.id(),
                "provider", provider,
                "from", clock.instant().minus(Duration.ofHours(1)).toString(),
                "to", clock.instant().plus(Duration.ofHours(1)).toString()));
    }

    private void anomaly(Map<String, Object> request) {
        assertThat(send("POST", "/simulator/" + ALPHA + "/report-anomalies", Map.of(), request).status()).isEqualTo(200);
    }

    private static List<String> exceptionTypes(Response run) {
        return list(run.body(), "exceptions").stream().map(e -> str(e, "type")).sorted().toList();
    }

    @Test
    void cleanDayMatchesEverythingAndTheReceivableNetsToZero() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> first = payAndSucceed(merchant, 100_000);
        payAndSucceed(merchant, 50_000);
        post(merchant, "/v1/payments/" + str(first, "id") + "/refunds", UUID.randomUUID().toString(), Map.of("amount", 10_000));

        Response run = reconcile(merchant, ALPHA);

        assertThat(run.status()).as(run.body().toString()).isEqualTo(201);
        assertThat(str(run.body(), "status")).isEqualTo("completed");
        assertThat(num(run.body(), "lines_total")).isEqualTo(3);
        assertThat(num(run.body(), "lines_matched")).isEqualTo(3);
        assertThat(num(run.body(), "exceptions_opened")).isZero();
        assertThat(num(run.body(), "gross_amount")).isEqualTo(150_000);
        assertThat(num(run.body(), "fee_amount")).isEqualTo(3_000);
        assertThat(num(run.body(), "settled_amount")).isEqualTo(137_000);
        assertThat(ledgerBalances(merchant))
                .containsEntry("psp_receivable", 0L)
                .containsEntry("sales_clearing", 150_000L)
                .containsEntry("refunds", 10_000L)
                .containsEntry("psp_fees", 3_000L)
                .containsEntry("bank_settlements", 137_000L);
    }

    @Test
    void captureThePspSettledButTheGatewayMissedIsAutoHealed() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 30_004, "automatic"), "id");
        assertThat(str(confirm(merchant, paymentId, upi("qr")).body(), "status")).isEqualTo("processing");

        Response run = reconcile(merchant, ALPHA);

        assertThat(num(run.body(), "lines_auto_healed")).isEqualTo(1);
        assertThat(num(run.body(), "exceptions_opened")).isZero();
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
        assertThat(ledgerBalances(merchant)).containsEntry("psp_receivable", 0L).containsEntry("sales_clearing", 30_004L);
    }

    @Test
    void orphanCaptureIsFlaggedAsMissingInternallyAndBreaksTheSettlement() {
        TestMerchant merchant = createMerchant(ALPHA);
        payAndSucceed(merchant, 100_000);
        anomaly(Map.of("type", "orphan_capture", "merchant_id", merchant.id(), "amount", 5_000));

        Response run = reconcile(merchant, ALPHA);

        assertThat(num(run.body(), "lines_matched")).isEqualTo(1);
        assertThat(exceptionTypes(run)).containsExactly("missing_internally", "settlement_mismatch");
        assertThat(ledgerBalances(merchant).get("psp_receivable")).as("residual equals the unmatched net").isEqualTo(-4_900L);
    }

    @Test
    void internalCaptureMissingFromTheReportIsFlaggedAndCanBeResolved() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 20_000);
        anomaly(Map.of("type", "drop", "provider_reference", str(payment, "latest_attempt.provider_reference")));

        Response run = reconcile(merchant, ALPHA);

        assertThat(exceptionTypes(run)).containsExactly("missing_at_provider");
        Map<String, Object> exception = list(run.body(), "exceptions").getFirst();
        assertThat(str(exception, "entity_id")).isEqualTo(str(payment, "latest_attempt.id"));
        Response resolved = admin("POST", "/admin/v1/reconciliation/exceptions/" + str(exception, "id") + "/resolve",
                Map.of("resolution", "PSP confirmed it settles in the next cycle"));
        assertThat(str(resolved.body(), "status")).isEqualTo("resolved");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'reconciliation_exception.resolved'")).isEqualTo(1);
        assertThat(list(admin("GET", "/admin/v1/reconciliation/exceptions?status=open", null).body(), "data")).isEmpty();
    }

    @Test
    void missingAtProviderIsAutoResolvedWhenALaterReportContainsIt() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 20_000);
        anomaly(Map.of("type", "drop", "provider_reference", str(payment, "latest_attempt.provider_reference")));
        assertThat(exceptionTypes(reconcile(merchant, ALPHA))).containsExactly("missing_at_provider");

        anomaly(Map.of("type", "clear"));
        Response rerun = reconcile(merchant, ALPHA);

        assertThat(num(rerun.body(), "lines_matched")).isEqualTo(1);
        assertThat(num(rerun.body(), "exceptions_opened")).isZero();
        assertThat(list(admin("GET", "/admin/v1/reconciliation/exceptions?status=resolved", null).body(), "data"))
                .extracting(e -> str(e, "resolution")).singleElement().asString().startsWith("auto-resolved");
    }

    @Test
    void amountMismatchIsFlaggedAndNeverHealed() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 100_000);
        anomaly(Map.of("type", "amount_override", "provider_reference", str(payment, "latest_attempt.provider_reference"),
                "amount", 90_000));

        Response run = reconcile(merchant, ALPHA);

        assertThat(exceptionTypes(run)).containsExactly("amount_mismatch", "settlement_mismatch");
        Map<String, Object> mismatch = list(run.body(), "exceptions").stream()
                .filter(e -> "amount_mismatch".equals(str(e, "type"))).findFirst().orElseThrow();
        assertThat(num(mismatch, "expected_amount")).isEqualTo(100_000);
        assertThat(num(mismatch, "actual_amount")).isEqualTo(90_000);
    }

    @Test
    void duplicateReportLinesAreFlagged() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 40_000);
        anomaly(Map.of("type", "duplicate", "provider_reference", str(payment, "latest_attempt.provider_reference")));

        Response run = reconcile(merchant, ALPHA);

        assertThat(num(run.body(), "lines_matched")).isEqualTo(1);
        assertThat(exceptionTypes(run)).containsExactly("duplicate", "settlement_mismatch");
    }

    @Test
    void shortPayoutIsFlaggedAsSettlementMismatch() {
        TestMerchant merchant = createMerchant(ALPHA);
        payAndSucceed(merchant, 100_000);
        anomaly(Map.of("type", "settlement_shortfall", "merchant_id", merchant.id(), "amount", 500));

        Response run = reconcile(merchant, ALPHA);

        assertThat(exceptionTypes(run)).containsExactly("settlement_mismatch");
        assertThat(num(list(run.body(), "exceptions").getFirst(), "actual_amount")).isEqualTo(97_500);
        assertThat(ledgerBalances(merchant).get("psp_receivable")).isEqualTo(500L);
    }

    @Test
    void rerunningTheSameWindowIsIdempotent() {
        TestMerchant merchant = createMerchant(ALPHA);
        payAndSucceed(merchant, 60_000);
        reconcile(merchant, ALPHA);
        long ledgerTransactions = count("SELECT count(*) FROM ledger_transactions");

        Response rerun = reconcile(merchant, ALPHA);

        assertThat(num(rerun.body(), "lines_matched")).isEqualTo(1);
        assertThat(num(rerun.body(), "exceptions_opened")).isZero();
        assertThat(count("SELECT count(*) FROM ledger_transactions")).isEqualTo(ledgerTransactions);
        assertThat(ledgerBalances(merchant)).containsEntry("psp_receivable", 0L);
    }

    @Test
    void providerMustBeLinkedToTheMerchant() {
        TestMerchant merchant = createMerchant(ALPHA);

        Response response = reconcile(merchant, BETA);

        assertThat(response.status()).isEqualTo(400);
        assertThat(str(response.body(), "code")).isEqualTo("validation_error");
    }
}
