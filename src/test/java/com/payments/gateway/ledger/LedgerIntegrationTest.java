package com.payments.gateway.ledger;

import com.payments.gateway.ledger.LedgerModel.Leg;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.shared.events.CurrencyConversion;
import com.payments.gateway.shared.events.CurrencyConversion.Kind;
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
        @Autowired
        private LedgerPostingListener postings;
        @Autowired
        private LedgerRepository repository;

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

    @Test
    void eachCurrencyMustBalanceEvenWhenTheCombinedTotalIsZero() {
        TestMerchant merchant = createMerchant(ALPHA);
        Posting unbalanced = new Posting(merchant.id(), ALPHA, LedgerTransactionType.FX_CONVERSION,
                "TEST", "cross-currency", "bad", clock.instant(), List.of(
                Leg.debit(LedgerAccountType.FX_CONVERSION, Money.of(100, "USD")),
                Leg.credit(LedgerAccountType.FX_CONVERSION, Money.of(100, "INR"))));
        assertThatThrownBy(() -> ledger.post(unbalanced)).hasMessageContaining("unbalanced");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            repository.insertTransaction("ltx_cross", unbalanced, clock.instant());
            for (Leg leg : unbalanced.legs()) {
                String accountId = repository.accountId(merchant.id(), ALPHA, leg.account(), leg.amount().currency(), clock.instant());
                repository.insertEntry("ltx_cross", accountId, leg.direction(), leg.amount(), clock.instant());
            }
        })).hasStackTraceContaining("not balanced");
        assertThat(count("SELECT count(*) FROM ledger_transactions")).isZero();

        Posting balanced = new Posting(merchant.id(), ALPHA, LedgerTransactionType.FX_CONVERSION,
                "TEST", "balanced-currencies", "balanced", clock.instant(), List.of(
                Leg.debit(LedgerAccountType.FX_CONVERSION, Money.of(100, "USD")),
                Leg.credit(LedgerAccountType.SALES_CLEARING, Money.of(100, "USD")),
                Leg.debit(LedgerAccountType.PSP_RECEIVABLE, Money.of(8325, "INR")),
                Leg.credit(LedgerAccountType.FX_CONVERSION, Money.of(8325, "INR"))));
        assertThat(ledger.post(balanced)).isTrue();
    }

    @Test
    void entriesCannotUseACurrencyDifferentFromTheirAccount() {
        TestMerchant merchant = createMerchant(ALPHA);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Posting posting = new Posting(merchant.id(), ALPHA, LedgerTransactionType.ADJUSTMENT,
                    "TEST", "wrong-currency", "bad", clock.instant(), List.of());
            repository.insertTransaction("ltx_wrong_currency", posting, clock.instant());
            String accountId = repository.accountId(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "INR", clock.instant());
            repository.insertEntry("ltx_wrong_currency", accountId, EntryDirection.DEBIT, Money.of(100, "USD"), clock.instant());
        })).hasStackTraceContaining("fk_ledger_entry_account_currency");
    }

    @Test
    void convertedRefundsBookTheDifferenceFromTheCaptureValue() {
        TestMerchant merchant = createMerchant(ALPHA);
        postings.on(new CurrencyConversion("capture", Kind.CAPTURE, merchant.id(), ALPHA,
                Money.of(834_567, "INR"), 834_567, clock.instant()));
        CurrencyConversion first = new CurrencyConversion("first", Kind.REFUND, merchant.id(), ALPHA,
                Money.of(340_000, "INR"), 333_827, clock.instant());
        postings.on(first);
        postings.on(first);
        postings.on(new CurrencyConversion("rest", Kind.REFUND, merchant.id(), ALPHA,
                Money.of(499_000, "INR"), 500_740, clock.instant()));
        assertThat(ledgerBalances(merchant)).containsEntry("fx_conversion", 0L)
                .containsEntry("fx_gain_loss", -4433L).containsEntry("psp_receivable", -4433L);
        assertThat(count("SELECT count(*) FROM ledger_transactions")).isEqualTo(3);
    }

    @Test
    void convertedChargebacksAndReversalsBookGainsAndLosses() {
        TestMerchant merchant = createMerchant(ALPHA);
        postings.on(new CurrencyConversion("chargeback", Kind.CHARGEBACK, merchant.id(), ALPHA,
                Money.of(90, "INR"), 100, clock.instant()));
        postings.on(new CurrencyConversion("reversal", Kind.CHARGEBACK_REVERSAL, merchant.id(), ALPHA,
                Money.of(120, "INR"), 100, clock.instant()));
        postings.on(new CurrencyConversion("chargeback2", Kind.CHARGEBACK, merchant.id(), ALPHA,
                Money.of(130, "INR"), 100, clock.instant()));
        postings.on(new CurrencyConversion("reversal2", Kind.CHARGEBACK_REVERSAL, merchant.id(), ALPHA,
                Money.of(80, "INR"), 100, clock.instant()));
        assertThat(ledgerBalances(merchant)).containsEntry("fx_conversion", 0L)
                .containsEntry("fx_gain_loss", -20L).containsEntry("psp_receivable", -20L);
    }
}
