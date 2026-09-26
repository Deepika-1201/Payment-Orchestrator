package com.payments.gateway.ledger;

import com.payments.gateway.ledger.LedgerModel.Leg;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LedgerIntegrationTest extends IntegrationTest {

    @Autowired
    private LedgerService ledger;
    @Autowired
    private TransactionTemplate tx;

    @Test
    void capturesAndRefundsArePostedAsBalancedDoubleEntries() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 50_000);
        post(merchant, "/v1/payments/" + str(payment, "id") + "/refunds", UUID.randomUUID().toString(), Map.of("amount", 20_000));

        Map<String, Long> balances = ledgerBalances(merchant);

        assertThat(balances).containsEntry("psp_receivable", 30_000L)
                .containsEntry("sales_clearing", 50_000L)
                .containsEntry("refunds", 20_000L);
        List<Map<String, Object>> transactions = list(admin("GET",
                "/admin/v1/ledger/transactions?reference_id=" + str(payment, "latest_attempt.id"), null).body(), "data");
        assertThat(transactions).hasSize(1);
        assertThat(str(transactions.getFirst(), "type")).isEqualTo("payment_captured");
        assertThat(list(transactions.getFirst(), "entries")).extracting(e -> e.get("account") + ":" + e.get("direction") + ":" + e.get("amount"))
                .containsExactly("psp_receivable:debit:50000", "sales_clearing:credit:50000");
    }

    @Test
    void lateSuccessCaptureAndItsAutomaticRefundNetToZero() {
        TestMerchant merchant = createMerchantWith(null, "auto_refund", ALPHA);
        String paymentId = str(createPayment(merchant, 55_500, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, upi("intent"));
        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireDue();

        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);

        assertThat(ledgerBalances(merchant)).containsEntry("psp_receivable", 0L)
                .containsEntry("sales_clearing", 55_500L)
                .containsEntry("refunds", 55_500L);
    }

    @Test
    void postingsAreIdempotentOnTheirReference() {
        TestMerchant merchant = createMerchant(ALPHA);
        Money fee = Money.of(250, "INR");
        Posting posting = new Posting(merchant.id(), ALPHA, LedgerTransactionType.PSP_FEE, "REPORT_LINE", "MOCK_ALPHA:line_x",
                "fee", clock.instant(), List.of(Leg.debit(LedgerAccountType.PSP_FEES, fee), Leg.credit(LedgerAccountType.PSP_RECEIVABLE, fee)));

        assertThat(ledger.post(posting)).isTrue();
        assertThat(ledger.post(posting)).isFalse();
        assertThat(count("SELECT count(*) FROM ledger_transactions")).isEqualTo(1);
        assertThat(ledgerBalances(merchant)).containsEntry("psp_fees", 250L).containsEntry("psp_receivable", -250L);
    }

    @Test
    void unbalancedTransactionsAreRejectedByServiceAndDatabase() {
        TestMerchant merchant = createMerchant(ALPHA);
        Posting unbalanced = new Posting(merchant.id(), ALPHA, LedgerTransactionType.PSP_FEE, "REPORT_LINE", "x", "bad",
                clock.instant(), List.of(Leg.debit(LedgerAccountType.PSP_FEES, Money.of(100, "INR")),
                Leg.credit(LedgerAccountType.PSP_RECEIVABLE, Money.of(99, "INR"))));
        assertThatThrownBy(() -> ledger.post(unbalanced)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unbalanced");

        jdbc.sql("""
                INSERT INTO ledger_accounts (id, merchant_id, provider_code, type, currency, normal_side, created_at)
                VALUES ('la_test', ?, 'MOCK_ALPHA', 'PSP_FEES', 'INR', 'DEBIT', now())
                """).param(1, merchant.id()).update();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO ledger_transactions (id, merchant_id, provider_code, type, reference_type, reference_id, occurred_at, created_at)
                    VALUES ('ltx_test', ?, 'MOCK_ALPHA', 'PSP_FEE', 'TEST', 'one-sided', now(), now())
                    """).param(1, merchant.id()).update();
            jdbc.sql("""
                    INSERT INTO ledger_entries (transaction_id, account_id, direction, amount, currency, created_at)
                    VALUES ('ltx_test', 'la_test', 'DEBIT', 100, 'INR', now())
                    """).update();
        })).hasStackTraceContaining("not balanced");
        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE id = 'ltx_test'")).isZero();
    }

    @Test
    void ledgerEntriesAreImmutable() {
        TestMerchant merchant = createMerchant(ALPHA);
        payAndSucceed(merchant, 10_000);

        Throwable error = catchThrowable(() -> jdbc.sql("UPDATE ledger_entries SET amount = amount + 1").update());

        assertThat(error).hasMessageContaining("append-only");
        assertThat(num(Map.of("v", count("SELECT count(*) FROM ledger_entries")), "v")).isEqualTo(2);
    }
}
