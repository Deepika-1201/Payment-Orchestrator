package com.payments.gateway.reconciliation;

import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** Working the reconciliation exception queue (owner, SLA) and the daily report (ADR-017). */
class ReconciliationOperationsIntegrationTest extends IntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired
    private ReconciliationService reconciliation;

    @Test
    void exceptionsGetADueDateCanBeAssignedAndSurfaceOnceOverdue() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 20_000);
        drop(payment);
        Map<String, Object> exception = list(reconcileAroundNow(merchant).body(), "exceptions").getFirst();
        String id = str(exception, "id");

        assertThat(Duration.between(Instant.parse(str(exception, "created_at")), Instant.parse(str(exception, "due_at"))))
                .isEqualTo(Duration.ofHours(48));
        assertThat(exception.get("overdue")).isEqualTo(false);
        assertThat(exception).doesNotContainKey("assignee");

        assertThat(str(assign(id, "ops-asha").body(), "assignee")).isEqualTo("ops-asha");
        Response reassigned = assign(id, "ops-ravi");
        assertThat(reassigned.status()).isEqualTo(200);
        assertThat(reassigned.body()).containsKey("assigned_at");
        assertThat(jdbc.sql("""
                SELECT details->>'previous_assignee' FROM audit_log
                 WHERE action = 'reconciliation_exception.assigned' AND details->>'assignee' = 'ops-ravi'
                """).query(String.class).single()).isEqualTo("ops-asha");
        assertThat(exceptions("?assignee=ops-ravi")).hasSize(1);
        assertThat(exceptions("?assignee=ops-asha")).isEmpty();
        assertThat(exceptions("?overdue=true")).isEmpty();

        clock.advance(Duration.ofHours(49));

        List<Map<String, Object>> overdue = exceptions("?overdue=true&merchant_id=" + merchant.id());
        assertThat(overdue).extracting(e -> e.get("id")).containsExactly(id);
        assertThat(overdue.getFirst().get("overdue")).isEqualTo(true);

        admin("POST", "/admin/v1/reconciliation/exceptions/" + id + "/resolve", Map.of("resolution", "Settles next cycle"));
        assertThat(exceptions("?overdue=true")).as("resolved exceptions are never overdue").isEmpty();
        assertThat(assign(id, "ops-asha").status()).isEqualTo(409);
        assertThat(assign("rex_missing", "ops-asha").status()).isEqualTo(404);
        assertThat(assign(id, " ").status()).isEqualTo(400);
    }

    @Test
    void dailyReportShowsEveryDueAccountTheDaysExceptionsAndTheBacklog() {
        clock.set(LocalDate.of(2026, 9, 24).atTime(11, 0).atZone(IST).toInstant());
        TestMerchant reconciled = createMerchant(ALPHA);
        drop(payAndSucceed(reconciled, 20_000));
        payAndSucceed(reconciled, 30_000);
        clock.set(LocalDate.of(2026, 9, 25).atTime(2, 30).atZone(IST).toInstant());
        assertThat(reconciliation.runForPreviousDay()).isEqualTo(1);
        TestMerchant notReconciled = createMerchant(ALPHA);

        Response report = admin("GET", "/admin/v1/reconciliation/reports/daily?date=2026-09-24", null);

        assertThat(report.status()).as(report.raw()).isEqualTo(200);
        assertThat(str(report.body(), "zone")).isEqualTo("Asia/Kolkata");
        assertThat(str(report.body(), "window_start")).isEqualTo("2026-09-23T18:30:00Z");
        assertThat(str(report.body(), "window_end")).isEqualTo("2026-09-24T18:30:00Z");
        Map<String, Object> done = account(report, reconciled);
        assertThat(str(done, "status")).isEqualTo("completed");
        assertThat(num(done, "lines_total")).isEqualTo(1);
        assertThat(num(done, "gross_amount")).isEqualTo(30_000);
        assertThat(num(done, "exceptions_opened")).isEqualTo(1);
        Map<String, Object> missing = account(report, notReconciled);
        assertThat(str(missing, "status")).as("due but never reconciled").isEqualTo("missing");
        assertThat(missing).doesNotContainKey("run_id");
        assertThat(num(report.body(), "exceptions.opened")).isEqualTo(1);
        assertThat(num(report.body(), "exceptions.open")).isEqualTo(1);
        assertThat(num(report.body(), "exceptions.resolved")).isZero();
        assertThat(report.body()).extracting(body -> ((Map<?, ?>) body.get("exceptions")).get("opened_by_type"))
                .isEqualTo(Map.of("missing_at_provider", 1));
        assertThat(num(report.body(), "backlog.open")).isEqualTo(1);
        assertThat(num(report.body(), "backlog.overdue")).isZero();

        Response filtered = admin("GET", "/admin/v1/reconciliation/reports/daily?date=2026-09-24&merchant_id="
                + notReconciled.id(), null);
        assertThat(list(filtered.body(), "accounts")).extracting(a -> a.get("merchant_id")).containsExactly(notReconciled.id());
        assertThat(num(filtered.body(), "exceptions.opened")).isZero();
        assertThat(num(filtered.body(), "backlog.open")).isZero();

        assertThat(admin("GET", "/admin/v1/reconciliation/reports/daily?date=2026-13-01", null).status()).isEqualTo(400);
        assertThat(admin("GET", "/admin/v1/reconciliation/reports/daily?date=2026-09-26", null).status())
                .as("future day").isEqualTo(400);
    }

    private Response reconcileAroundNow(TestMerchant merchant) {
        return admin("POST", "/admin/v1/reconciliation/runs", Map.of(
                "merchant_id", merchant.id(),
                "provider", ALPHA,
                "from", clock.instant().minus(Duration.ofHours(1)).toString(),
                "to", clock.instant().plus(Duration.ofHours(1)).toString()));
    }

    private void drop(Map<String, Object> payment) {
        assertThat(send("POST", "/simulator/" + ALPHA + "/report-anomalies", Map.of(),
                Map.of("type", "drop", "provider_reference", str(payment, "latest_attempt.provider_reference"))).status())
                .isEqualTo(200);
    }

    private Response assign(String exceptionId, String assignee) {
        return admin("POST", "/admin/v1/reconciliation/exceptions/" + exceptionId + "/assign", Map.of("assignee", assignee));
    }

    private List<Map<String, Object>> exceptions(String query) {
        return list(admin("GET", "/admin/v1/reconciliation/exceptions" + query, null).body(), "data");
    }

    private static Map<String, Object> account(Response report, TestMerchant merchant) {
        return list(report.body(), "accounts").stream()
                .filter(a -> merchant.id().equals(a.get("merchant_id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no account for " + merchant.id() + " in " + report.raw()));
    }
}
