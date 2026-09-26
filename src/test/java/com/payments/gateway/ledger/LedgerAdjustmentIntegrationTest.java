package com.payments.gateway.ledger;

import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** Maker-checker manual ledger adjustments (ADR-024), with operators from application-test.yml. */
class LedgerAdjustmentIntegrationTest extends IntegrationTest {

    private static final String FINANCE = "test-finance-token";
    private static final String OPS = "test-ops-token";

    @Test
    void aShortPayoutIsWrittenOffOnlyAfterASecondOperatorApproves() {
        TestMerchant merchant = shortPaidMerchant();
        assertThat(ledgerBalances(merchant)).containsEntry("psp_receivable", 500L);

        Response requested = as(FINANCE, "POST", "/admin/v1/ledger/adjustments", writeOff(merchant, 500));

        assertThat(requested.status()).as(requested.raw()).isEqualTo(201);
        assertThat(str(requested.body(), "status")).isEqualTo("pending");
        assertThat(str(requested.body(), "requested_by")).isEqualTo("finance-ravi");
        assertThat(ledgerBalances(merchant)).as("nothing posted yet").containsEntry("psp_receivable", 500L);
        String approvePath = "/admin/v1/ledger/adjustments/" + str(requested.body(), "id") + "/approve";

        Response selfApproved = as(FINANCE, "POST", approvePath, Map.of("note", "looks right to me"));
        assertThat(selfApproved.status()).isEqualTo(403);
        assertThat(str(selfApproved.body(), "detail")).contains("different operator");
        assertThat(as(OPS, "POST", approvePath, Map.of("note", "ops cannot")).status()).isEqualTo(403);

        Response approved = admin("POST", approvePath, Map.of("note", "PSP confirmed the 500 was withheld as a fee"));

        assertThat(approved.status()).as(approved.raw()).isEqualTo(200);
        assertThat(str(approved.body(), "status")).isEqualTo("approved");
        assertThat(str(approved.body(), "decided_by")).isEqualTo("admin-token-0");
        assertThat(ledgerBalances(merchant)).containsEntry("psp_receivable", 0L).containsEntry("psp_fees", 2_500L);
        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE resource_type = 'ledger_adjustment' ORDER BY id")
                .query(String.class).list()).containsExactly("ledger_adjustment.requested", "ledger_adjustment.approved");
        assertThat(admin("POST", approvePath, Map.of("note", "again")).status()).isEqualTo(409);
    }

    @Test
    void rejectedOrExpiredAdjustmentsNeverPost() {
        TestMerchant merchant = shortPaidMerchant();
        String withdrawn = str(as(FINANCE, "POST", "/admin/v1/ledger/adjustments", writeOff(merchant, 500)).body(), "id");
        assertThat(str(as(FINANCE, "POST", "/admin/v1/ledger/adjustments/" + withdrawn + "/reject",
                Map.of("note", "wrong account")).body(), "status")).isEqualTo("rejected");
        assertThat(admin("POST", "/admin/v1/ledger/adjustments/" + withdrawn + "/approve", Map.of("note", "x")).status())
                .isEqualTo(409);

        String stale = str(as(FINANCE, "POST", "/admin/v1/ledger/adjustments", writeOff(merchant, 500)).body(), "id");
        clock.advance(Duration.ofDays(8));
        assertThat(str(admin("GET", "/admin/v1/ledger/adjustments/" + stale, null).body(), "status")).isEqualTo("expired");
        assertThat(admin("POST", "/admin/v1/ledger/adjustments/" + stale + "/approve", Map.of("note", "late")).status())
                .isEqualTo(409);

        assertThat(ledgerBalances(merchant)).containsEntry("psp_receivable", 500L);
        assertThat(list(admin("GET", "/admin/v1/ledger/adjustments?merchant_id=" + merchant.id(), null).body(), "data"))
                .hasSize(2);
        Map<String, Object> sameAccount = writeOff(merchant, 500);
        sameAccount.put("debit_account", "psp_receivable");
        assertThat(as(FINANCE, "POST", "/admin/v1/ledger/adjustments", sameAccount).status()).isEqualTo(400);
    }

    @Test
    void theDatabaseItselfRefusesASelfApprovedAdjustment() {
        TestMerchant merchant = createMerchant(ALPHA);
        String id = str(as(FINANCE, "POST", "/admin/v1/ledger/adjustments", writeOff(merchant, 100)).body(), "id");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                        "UPDATE ledger_adjustments SET status = 'APPROVED', decided_by = requested_by WHERE id = ?")
                .param(1, id).update())
                .hasMessageContaining("ck_adjustment_four_eyes");
    }

    private TestMerchant shortPaidMerchant() {
        TestMerchant merchant = createMerchant(ALPHA);
        payAndSucceed(merchant, 100_000);
        assertThat(send("POST", "/simulator/" + ALPHA + "/report-anomalies", Map.of(),
                Map.of("type", "settlement_shortfall", "merchant_id", merchant.id(), "amount", 500)).status()).isEqualTo(200);
        Response run = admin("POST", "/admin/v1/reconciliation/runs", Map.of("merchant_id", merchant.id(), "provider", ALPHA,
                "from", clock.instant().minus(Duration.ofHours(1)).toString(),
                "to", clock.instant().plus(Duration.ofHours(1)).toString()));
        assertThat(run.status()).isEqualTo(201);
        return merchant;
    }

    private static Map<String, Object> writeOff(TestMerchant merchant, long amount) {
        Map<String, Object> body = new HashMap<>(Map.of("merchant_id", merchant.id(), "provider", ALPHA,
                "debit_account", "psp_fees", "credit_account", "psp_receivable", "amount", amount,
                "reason", "Settlement shortfall withheld by the PSP as an unreported fee"));
        body.put("reference", "reconciliation shortfall");
        return body;
    }

    private Response as(String token, String method, String path, Object body) {
        return send(method, path, Map.of("Authorization", "Bearer " + token), body);
    }
}
