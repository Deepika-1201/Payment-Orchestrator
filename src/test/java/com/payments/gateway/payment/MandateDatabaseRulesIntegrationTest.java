package com.payments.gateway.payment;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** NFR-19: the database itself refuses debits that break a mandate's rules, whatever the application does. */
class MandateDatabaseRulesIntegrationTest extends MandateTestSupport {

    private static final String NOTIFIED = "timestamptz '2026-10-01 10:00:00+00'";

    private TestMerchant merchant;
    private String mandateId;
    private String debitId;

    @BeforeEach
    void activeMandateWithADebit() {
        merchant = createMerchant(ALPHA);
        mandateId = activeMandate(merchant, "upi_autopay", 500_000);
        debitId = createDebit(merchant, mandateId, 49_900, "inv_1");
    }

    @Test
    void amountsAreFixedWhenTheDebitIsCreated() {
        Throwable amount = catchThrowable(() -> jdbc.sql("UPDATE mandate_debits SET amount = amount - 1 WHERE id = ?")
                .param(1, debitId).update());
        Throwable limit = catchThrowable(() -> jdbc.sql(
                "UPDATE mandate_debits SET frictionless_limit = frictionless_limit + 1 WHERE id = ?").param(1, debitId).update());

        assertThat(amount).hasStackTraceContaining("cannot change");
        assertThat(limit).hasStackTraceContaining("cannot change");
    }

    @Test
    void aDebitCarriesItsMandatesMaxAmountAndStaysWithinItsLimits() {
        cancelTheDebit();
        String paymentId = str(createPayment(merchant, 49_900, "automatic"), "id");

        assertThat(catchThrowable(() -> insertDebit("mdd_other_max", paymentId, 49_900, 600_000, 1_500_000)))
                .hasStackTraceContaining("must carry the max_amount");
        assertThat(catchThrowable(() -> insertDebit("mdd_above_max", paymentId, 500_001, 500_000, 1_500_000)))
                .hasStackTraceContaining("ck_mandate_debits_within_max");
        assertThat(catchThrowable(() -> insertDebit("mdd_above_limit", paymentId, 49_900, 500_000, 40_000)))
                .hasStackTraceContaining("ck_mandate_debits_frictionless");
        insertDebit("mdd_valid", paymentId, 500_000, 500_000, 500_000);
        assertThat(count("SELECT count(*) FROM mandate_debits WHERE mandate_id = ?", mandateId)).isEqualTo(2);
    }

    @Test
    void aMandateHasAtMostOneDebitInProgress() {
        String paymentId = str(createPayment(merchant, 49_900, "automatic"), "id");

        assertThat(catchThrowable(() -> insertDebit("mdd_second", paymentId, 49_900, 500_000, 1_500_000)))
                .hasStackTraceContaining("ux_mandate_debits_in_progress");
    }

    @Test
    void onlyAnActiveMandatesDebitExecutes() {
        jdbc.sql("UPDATE mandates SET status = 'PAUSED' WHERE id = ?").param(1, mandateId).update();

        assertThat(catchThrowable(this::execute25HoursAfterTheNotice)).hasStackTraceContaining("cannot execute");

        jdbc.sql("UPDATE mandates SET status = 'ACTIVE' WHERE id = ?").param(1, mandateId).update();
        assertThat(execute25HoursAfterTheNotice()).isEqualTo(1);
    }

    @Test
    void aNotifiedDebitExecutesOnlyAfterTheNoticePeriod() {
        Throwable early = catchThrowable(() -> jdbc.sql("UPDATE mandate_debits SET notified_at = " + NOTIFIED
                + ", last_executed_at = " + NOTIFIED + " + interval '23 hours 59 minutes' WHERE id = ?")
                .param(1, debitId).update());
        Throwable neverNotified = catchThrowable(() -> jdbc.sql("UPDATE mandate_debits SET notified_at = NULL"
                + ", last_executed_at = " + NOTIFIED + " WHERE id = ?").param(1, debitId).update());

        assertThat(early).hasStackTraceContaining("ck_mandate_debits_notice");
        assertThat(neverNotified).as("NULL must not pass the check").hasStackTraceContaining("ck_mandate_debits_notice");
        assertThat(jdbc.sql("UPDATE mandate_debits SET notified_at = " + NOTIFIED + ", last_executed_at = " + NOTIFIED
                + " + interval '24 hours' WHERE id = ?").param(1, debitId).update()).isEqualTo(1);
    }

    @Test
    void theMandateHistoryIsAppendOnly() {
        assertThat(count("SELECT count(*) FROM mandate_transitions WHERE mandate_id = ?", mandateId)).isPositive();

        Throwable update = catchThrowable(() -> jdbc.sql("UPDATE mandate_transitions SET reason = 'rewritten'").update());
        Throwable delete = catchThrowable(() -> jdbc.sql("DELETE FROM mandate_transitions").update());

        assertThat(update).hasStackTraceContaining("append-only");
        assertThat(delete).hasStackTraceContaining("append-only");
    }

    private int execute25HoursAfterTheNotice() {
        return jdbc.sql("UPDATE mandate_debits SET notified_at = " + NOTIFIED + ", last_executed_at = " + NOTIFIED
                + " + interval '25 hours' WHERE id = ?").param(1, debitId).update();
    }

    private void cancelTheDebit() {
        Response cancelled = post(merchant, "/v1/mandates/" + mandateId + "/debits/" + debitId + "/cancel", key(), null);
        assertThat(cancelled.status()).as(cancelled.raw()).isEqualTo(200);
    }

    private void insertDebit(String id, String paymentId, long amount, long maxAmount, long frictionlessLimit) {
        jdbc.sql("""
                INSERT INTO mandate_debits (id, mandate_id, merchant_id, payment_id, merchant_debit_id, amount, currency,
                                            max_amount, frictionless_limit, requires_notification, status, due_at,
                                            not_before, cycle, version, created_at, updated_at)
                VALUES (:id, :mandateId, :merchantId, :paymentId, :id, :amount, 'INR', :maxAmount, :frictionlessLimit, true,
                        'SCHEDULED', now(), now(), 1, 0, now(), now())
                """)
                .params(Map.of("id", id, "mandateId", mandateId, "merchantId", merchant.id(), "paymentId", paymentId,
                        "amount", amount, "maxAmount", maxAmount, "frictionlessLimit", frictionlessLimit))
                .update();
    }
}
