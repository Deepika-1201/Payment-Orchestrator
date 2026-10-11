package com.payments.gateway.payout;

import com.payments.gateway.ledger.LedgerAccountType;
import com.payments.gateway.ledger.LedgerModel.Leg;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.ledger.LedgerTransactionType;
import com.payments.gateway.merchant.AggregationProperties;
import com.payments.gateway.merchant.KycDetails;
import com.payments.gateway.merchant.OnboardingService;
import com.payments.gateway.payout.mock.MockPayoutProvider;
import com.payments.gateway.payout.spi.PayoutProvider.Outcome;
import com.payments.gateway.payout.spi.PayoutProvider;
import com.payments.gateway.reconciliation.ReconciliationService;
import com.payments.gateway.support.IntegrationTest;
import com.payments.gateway.treasury.SettlementService;
import com.payments.gateway.treasury.TreasuryService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.test.util.AopTestUtils;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"pg.aggregation.enabled=true", "pg.aggregation.platform-merchant-id=payout-owner",
    "pg.aggregation.collections-enabled=true", "pg.payouts.enabled=true", "pg.escrow.require-current-statement=false"})
class PayoutIntegrationTest extends IntegrationTest {
    @Autowired
    private OnboardingService onboarding;
    @Autowired
    private SettlementService settlements;
    @Autowired
    private TreasuryService treasury;
    @Autowired
    private LedgerService ledger;
    @Autowired
    private ReconciliationService reconciliation;
    @Autowired
    private PayoutService payouts;
    @MockitoSpyBean
    private PayoutRepository repository;
    @Autowired
    private MockPayoutProvider remote;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private BankReconciliationService bank;
    @Autowired
    private PayoutWebhookService webhooks;
    @Autowired
    private EscrowAdjustmentService adjustments;
    @Autowired
    private EscrowMonitor monitor;
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired
    private SecretCipher cipher;
    @MockitoSpyBean
    private EscrowProperties escrow;
    @MockitoSpyBean
    private PayoutProperties properties;
    @MockitoSpyBean
    private PayoutProviders payoutProviders;
    @MockitoSpyBean
    private AggregationProperties aggregation;

    private TestMerchant owner;
    private TestMerchant merchant;

    @BeforeEach
    void enroll() {
        owner = createMerchant(ALPHA);
        merchant = createMerchant();
        doReturn(owner.id()).when(aggregation).platformMerchantId();
        var details = new KycDetails("Payout Merchant", "ABCDE1234F", "27ABCDE1234F1Z5",
                new KycDetails.BankAccount("123456789012", "HDFC0001234", "Payout Merchant"),
                List.of(new KycDetails.BeneficialOwner("Owner", "BCDEF2345G", 10_000)), KycDetails.Risk.LOW,
                new KycDetails.Verification("kyc", "background", "bank", clock.instant()),
                Map.of("INR", new KycDetails.Limits(100_000_000, 1_000_000_000)));
        var submission = onboarding.submit(merchant.id(), details, "maker");
        onboarding.review(merchant.id(), submission.id(), true, "checker");
    }

    @Test
    void payoutIsQueuedOnceDispatchedOnceAndConfirmedReturnRestoresPayableOnce() {
        String batch = batch(10_000, 0);
        var queued = payouts.queue(merchant.id(), batch, "finance");
        assertThat(queued.status()).isEqualTo("queued");
        assertThat(payouts.queue(merchant.id(), batch, "finance").id()).isEqualTo(queued.id());
        payouts.advance(queued.id());
        assertThat(payouts.get(merchant.id(), queued.id()).status()).isEqualTo("succeeded");
        assertThat(remote.createCalls(queued.id())).isEqualTo(1);
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isZero();
        assertThat(treasury.balance(merchant.id()).escrow()).isZero();
        payouts.advance(queued.id());
        assertThat(remote.createCalls(queued.id())).isEqualTo(1);
        remote.move(queued.id(), Outcome.RETURNED);
        payouts.check(merchant.id(), queued.id());
        payouts.check(merchant.id(), queued.id());
        assertThat(payouts.get(merchant.id(), queued.id()).status()).isEqualTo("returned");
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(9800);
        assertThat(treasury.balance(merchant.id()).escrow()).isEqualTo(9800);
        assertThat(treasury.balance(null).difference()).isZero();
        assertThat(count("SELECT count(*) FROM treasury_exceptions WHERE type = 'PAYOUT_RETURNED'")).isEqualTo(1);
        assertThatThrownBy(() -> settlements.prepare(merchant.id(), "return-needs-review", "maker")).hasMessageContaining("review");
    }

    @Test
    void processedTimeoutAndDurableDispatchCrashUseOnlyStatusChecks() {
        String batch = batch(10_000, 99);
        var queued = payouts.queue(merchant.id(), batch, "finance");
        assertThat(queued.amount()).isEqualTo(9701);
        payouts.advance(queued.id());
        assertThat(payouts.get(merchant.id(), queued.id()).status()).isEqualTo("pending");
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isEqualTo(9701);
        payouts.check(merchant.id(), queued.id());
        assertThat(payouts.get(merchant.id(), queued.id()).status()).isEqualTo("succeeded");
        assertThat(remote.createCalls(queued.id())).isEqualTo(1);
    }

    @Test
    void crashBeforeTheOnlySendStaysReservedAndVisibleInsteadOfResending() {
        String batch = batch(10_000, 0);
        var queued = payouts.queue(merchant.id(), batch, "finance");
        tx.executeWithoutResult(status -> {
            treasury.lock();
            assertThat(repository.dispatch(queued.id(), clock.instant(), clock.instant().plusSeconds(1))).isTrue();
        });
        payouts.advance(queued.id());
        payouts.advance(queued.id());
        assertThat(remote.createCalls(queued.id())).isZero();
        assertThat(payouts.get(merchant.id(), queued.id()).status()).isEqualTo("pending");
        clock.advance(Duration.ofMinutes(31));
        payouts.advance(queued.id());
        assertThat(payouts.get(merchant.id(), queued.id()).status()).isEqualTo("review");
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isEqualTo(9800);
        assertThat(count("SELECT count(*) FROM treasury_exceptions WHERE type = 'PAYOUT_UNRESOLVED'")).isEqualTo(1);
        assertThatThrownBy(() -> payouts.cancel(merchant.id(), queued.id(), "finance")).hasMessageContaining("undispatched");
    }

    @Test
    void cancellationBeforeDispatchReleasesOnlyOnce() {
        String batch = batch(10_000, 0);
        var queued = payouts.queue(merchant.id(), batch, "finance");
        assertThat(payouts.cancel(merchant.id(), queued.id(), "finance").status()).isEqualTo("cancelled");
        assertThat(payouts.cancel(merchant.id(), queued.id(), "finance").status()).isEqualTo("cancelled");
        payouts.advance(queued.id());
        assertThat(remote.createCalls(queued.id())).isZero();
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isZero();
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(9800);
        assertThat(treasury.balance(merchant.id()).escrow()).isEqualTo(9800);
        assertThat(treasury.balance(null).difference()).isZero();
    }

    @Test
    void signedWebhooksAreDurableDeduplicatedAndCannotRegressAReturn() {
        String batch = batch(10_000, 96);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        payouts.advance(payout.id());
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("pending");
        var returned = remote.move(payout.id(), Outcome.RETURNED);
        String body = json.write(new PayoutProvider.Event("returned-event", payout.id(), returned));
        String path = "/v1/webhooks/payouts/" + MockPayoutProvider.CODE;
        assertThat(send("POST", path, Map.of("X-Payout-Signature", "bad"), body).status()).isEqualTo(401);
        var accepted = send("POST", path, Map.of("X-Payout-Signature", remote.sign(body)), body);
        assertThat(accepted.status()).as(accepted.raw()).isEqualTo(200);
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("returned");
        assertThat(send("POST", path, Map.of("X-Payout-Signature", remote.sign(body)), body).body().get("duplicates")).isEqualTo(1);
        var stale = new PayoutProvider.Result(Outcome.SUCCEEDED, returned.providerReference(), returned.referenceId(), returned.amount(),
                returned.destination(), returned.bankReference(), null);
        String staleBody = json.write(new PayoutProvider.Event("old-success", payout.id(), stale));
        assertThat(send("POST", path, Map.of("X-Payout-Signature", remote.sign(staleBody)), staleBody).status()).isEqualTo(200);
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("returned");
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(9704);
        assertThat(count("SELECT count(*) FROM payout_webhook_inbox WHERE status = 'DONE'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE type = 'PAYOUT_RETURNED'")).isEqualTo(1);
    }

        @Test
        void tamperedWebhookRemainsPendingAndRetriesOnlyAfterBackoff() {
        var payout = payouts.queue(merchant.id(), batch(10_000, 96), "finance");
        payouts.advance(payout.id());
        var original = remote.result(payout.id());
        var event = new PayoutProvider.Event("fingerprint-event", payout.id(), original);
        var altered = new PayoutProvider.Result(Outcome.SUCCEEDED, original.providerReference(), payout.id(), original.amount(),
            original.destination(), original.bankReference(), null);
        String inboxId = "pwi_tampered";
        String context = PayoutRepository.inboxContext(inboxId);
        byte[] changed = cipher.encrypt(json.write(new PayoutProvider.Event(event.id(), payout.id(), altered)), context);
        var stored = new PayoutRepository.Inbox(inboxId, MockPayoutProvider.CODE, payout.id(), changed,
            com.payments.gateway.shared.crypto.Hashing.sha256(json.write(event)), 0);
        assertThat(repository.inbox(stored, event.id(), clock.instant())).isTrue();
        assertThat(webhooks.run()).isEqualTo(1);
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("pending");
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isEqualTo(9704);
        assertThat(count("SELECT count(*) FROM payout_webhook_inbox WHERE status = 'PENDING' AND attempts = 1")).isEqualTo(1);
        assertThat(webhooks.run()).isZero();
        assertThat(repository.swapKeyRingCiphertext("event:" + inboxId, changed, cipher.encrypt(json.write(event), context))).isTrue();
        clock.advance(Duration.ofSeconds(31));
        assertThat(webhooks.run()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payout_webhook_inbox WHERE status = 'DONE'")).isEqualTo(1);
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("pending");
        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE type = 'PAYOUT_SENT'")).isZero();
        }

        @Test
        void aNewHoldLeavesTheQueuedInstructionCancellableWithoutASend() {
        String batch = batch(10_000, 0);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        clock.advance(Duration.ofSeconds(1));
        settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, true, "registered", "hold payout"), "finance");
        payouts.advance(payout.id());
        var held = payouts.get(merchant.id(), payout.id());
        assertThat(held.status()).isEqualTo("queued");
        assertThat(held.reason()).isEqualTo("dispatch_held");
        assertThat(remote.createCalls(payout.id())).isZero();
        assertThat(payouts.cancel(merchant.id(), payout.id(), "finance").status()).isEqualTo("cancelled");
    }

    @Test
    void bankStatementMatchesReceiptAndPayoutAndReplaysWithoutLosingItsClosingBalance() {
        var from = clock.instant().minusSeconds(1);
        String batch = batch(10_000, 0);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        payouts.advance(payout.id());
        var bookedAt = clock.instant();
        String receipt = jdbc.sql("SELECT id FROM escrow_receipts").query(String.class).single();
        clock.advance(Duration.ofSeconds(1));
        var statement = new BankStatement(null, "bank-day", "primary-escrow", from, clock.instant(), 0, 0, List.of(
                new BankStatement.Entry("credit-1", BankStatement.Kind.PSP_RECEIPT, receipt, 9800, bookedAt),
                new BankStatement.Entry("debit-1", BankStatement.Kind.PAYOUT_DEBIT, payout.id(), -9800, bookedAt)), null);
        var imported = bank.ingest(statement, "finance");
        assertThat(imported.reconciliation().matched()).isTrue();
        assertThat(bank.get(imported.statement().id()).entries()).hasSize(2);
        assertThat(bank.ingest(statement, "finance").statement().id()).isEqualTo(imported.statement().id());
        assertThat(count("SELECT count(*) FROM escrow_bank_statements")).isEqualTo(1);
    }

        @Test
        void pooledBankReceiptMatchesTheSumOfEveryMerchantAllocation() {
        var from = clock.instant().minusSeconds(1);
        var second = createMerchant();
        var details = new KycDetails("Second Merchant", "ABCDE1234F", "27ABCDE1234F1Z5",
            new KycDetails.BankAccount("123456789013", "HDFC0001234", "Second Merchant"),
            List.of(new KycDetails.BeneficialOwner("Owner", "BCDEF2345G", 10_000)), KycDetails.Risk.LOW,
            new KycDetails.Verification("kyc", "background", "bank", clock.instant()),
            Map.of("INR", new KycDetails.Limits(100_000_000, 1_000_000_000)));
        var submission = onboarding.submit(second.id(), details, "maker");
        onboarding.review(second.id(), submission.id(), true, "checker");
        payAndSucceed(merchant, 10_000);
        payAndSucceed(second, 20_000);
        clock.advance(Duration.ofDays(2));
        reconciliation.run(owner.id(), ALPHA, from, clock.instant());
        String receipt = jdbc.sql("SELECT id FROM escrow_receipts").query(String.class).single();
        var booked = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        var imported = bank.ingest(new BankStatement(null, "pooled-bank", "primary-escrow", from, clock.instant(), 0, 29_400,
            List.of(new BankStatement.Entry("pooled-credit", BankStatement.Kind.PSP_RECEIPT, receipt, 29_400, booked)), null), "finance");
        assertThat(imported.reconciliation().matched()).isTrue();
        assertThat(treasury.balance(merchant.id()).escrow()).isEqualTo(9800);
        assertThat(treasury.balance(second.id()).escrow()).isEqualTo(19_600);
        }

        @Test
        void importedEvidenceMustUseTheConfiguredAccountAPastCutoffAndAnImmutableReference() {
        var from = clock.instant().minusSeconds(10);
        var now = clock.instant();
        assertThatThrownBy(() -> bank.ingest(new BankStatement(null, "future", "primary-escrow", from, now.plusSeconds(1),
            0, 0, List.of(), null), "finance")).hasMessageContaining("cannot be in the future");
        assertThatThrownBy(() -> bank.ingest(new BankStatement(null, "wrong-account", "another-bank", from, now,
            0, 0, List.of(), null), "finance")).hasMessageContaining("account_reference");
        var credit = new BankStatement.Entry("unmatched-credit", BankStatement.Kind.UNMATCHED, "original-reference", 100, from);
        var debit = new BankStatement.Entry("unmatched-debit", BankStatement.Kind.UNMATCHED, "debit", -100, from);
        var original = bank.ingest(new BankStatement(null, "immutable-evidence", "primary-escrow", from, now, 0, 0, List.of(credit, debit), null), "finance");
        var changed = new BankStatement.Entry(credit.bankEntryId(), credit.kind(), "changed-reference", credit.amount(), credit.bookedAt());
        assertThatThrownBy(() -> bank.ingest(new BankStatement(null, "immutable-evidence", "primary-escrow", from, now, 0, 0,
            List.of(changed, debit), null), "finance")).hasMessageContaining("reused with different bank evidence");
        assertThat(bank.get(original.statement().id()).entries()).contains(credit);
        assertThat(count("SELECT count(*) FROM escrow_bank_statements")).isEqualTo(1);
        assertThatThrownBy(() -> bank.ingest(new BankStatement(null, "conflicting-export", "primary-escrow", from, now, 0, 0,
            List.of(changed, debit), null), "finance")).hasMessageContaining("bank entry id reused with different facts");
        assertThatThrownBy(() -> jdbc.sql("""
            INSERT INTO escrow_bank_entries (statement_id, bank_entry_id, kind, reference_id, amount, booked_at)
            VALUES (:statement, 'late-entry', 'UNMATCHED', 'late-reference', 1, :booked)
            """).param("statement", original.statement().id()).param("booked", com.payments.gateway.shared.jdbc.Sql.ts(from)).update())
            .hasMessageContaining("statement creation transaction");
        assertThatThrownBy(() -> jdbc.sql("""
            INSERT INTO escrow_bank_statements (id, reference, account_reference, window_start, window_end,
                opening_balance, closing_balance, imported_at, imported_by)
            VALUES ('bst_unbalanced', 'unbalanced-header', 'primary-escrow', :start, :end, 0, 1, :end, 'finance')
            """).param("start", com.payments.gateway.shared.jdbc.Sql.ts(from)).param("end", com.payments.gateway.shared.jdbc.Sql.ts(now)).update())
            .hasMessageContaining("balances must match its entries");
        }

        @Test
        void knownTransitIsDerivedFromTheBookAndCannotBeAnArbitraryPlug() {
        var from = clock.instant().minusSeconds(1);
        batch(10_000, 0);
        String receipt = jdbc.sql("SELECT id FROM escrow_receipts").query(String.class).single();
        clock.advance(Duration.ofSeconds(1));
        var imported = bank.ingest(new BankStatement(null, "not-yet-in-bank", "primary-escrow", from, clock.instant(), 0, 0, List.of(), null), "finance");
        assertThat(imported.reconciliation().matched()).isFalse();
        assertThat(imported.reconciliation().difference()).isEqualTo(-9800);
        var explained = bank.reconcile(imported.statement().id(), List.of(new BankReconciliationService.Transit(BankStatement.Kind.PSP_RECEIPT, receipt)));
        assertThat(explained.transitAmount()).isEqualTo(9800);
        assertThat(explained.matched()).isTrue();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            treasury.lock();
            settlements.checkCash(merchant.id(), 0);
        })).hasMessageContaining("escrow cash");
        assertThat(jdbc.sql("SELECT amount FROM escrow_cash_holds WHERE merchant_id = :merchant")
            .param("merchant", merchant.id()).query(Long.class).single()).isEqualTo(9800);
        var invalid = bank.reconcile(imported.statement().id(), List.of(new BankReconciliationService.Transit(BankStatement.Kind.PSP_RECEIPT, "invented")));
        assertThat(invalid.matched()).isFalse();
        assertThat(invalid.transitAmount()).isZero();
    }

        @Test
        void duplicateAndMissingBankMovementsCannotHideBehindANetZeroBalance() {
        var from = clock.instant().minusSeconds(1);
        var payout = payouts.queue(merchant.id(), batch(10_000, 0), "finance");
        payouts.advance(payout.id());
        String receipt = jdbc.sql("SELECT id FROM escrow_receipts").query(String.class).single();
        var booked = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        var missing = bank.ingest(new BankStatement(null, "missing-zero-net", "primary-escrow", from, clock.instant(), 0, 0, List.of(), null), "finance");
        assertThat(missing.reconciliation().issues()).containsExactlyInAnyOrder("missing_bank_movement:" + receipt,
            "missing_bank_movement:" + payout.id());
        var credit = new BankStatement.Entry("credit-original", BankStatement.Kind.PSP_RECEIPT, receipt, 9800, booked);
        var debit = new BankStatement.Entry("debit-original", BankStatement.Kind.PAYOUT_DEBIT, payout.id(), -9800, booked);
        var duplicate = bank.ingest(new BankStatement(null, "duplicate-zero-net", "primary-escrow", from, clock.instant(), 0, 0, List.of(credit, debit,
            new BankStatement.Entry("credit-copy", credit.kind(), credit.referenceId(), credit.amount(), booked),
            new BankStatement.Entry("debit-copy", debit.kind(), debit.referenceId(), debit.amount(), booked)), null), "finance");
        assertThat(duplicate.reconciliation().issues()).containsExactlyInAnyOrder("bank_entry_mismatch:credit-copy", "bank_entry_mismatch:debit-copy");
        var correct = bank.ingest(new BankStatement(null, "correct-zero-net", "primary-escrow", from, clock.instant(), 0, 0, List.of(credit, debit), null), "finance");
        assertThat(correct.reconciliation().matched()).isTrue();
        assertThat(bank.reconcile(correct.statement().id(), List.of(new BankReconciliationService.Transit(credit.kind(), receipt))).issues())
            .contains("invalid_in_transit:" + receipt);
        }

        @Test
        void openingBalanceMustAccountForCashPostedBeforeTheStatementWindow() {
        batch(10_000, 0);
        clock.advance(Duration.ofSeconds(1));
        var from = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        var imported = bank.ingest(new BankStatement(null, "wrong-opening", "primary-escrow", from, clock.instant(), 0, 0, List.of(), null), "finance");
        assertThat(imported.reconciliation().issues()).contains("opening_balance_mismatch");
        assertThat(imported.reconciliation().matched()).isFalse();
        }

        @Test
        void equalAndOppositeWrongBankEntriesStillRaiseAnImmediateException() {
        var from = clock.instant().minusSeconds(1);
        String batch = batch(10_000, 0);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        payouts.advance(payout.id());
        var bookedAt = clock.instant();
        String receipt = jdbc.sql("SELECT id FROM escrow_receipts").query(String.class).single();
        clock.advance(Duration.ofSeconds(1));
        var imported = bank.ingest(new BankStatement(null, "incorrect-lines", "primary-escrow", from, clock.instant(), 0, 0, List.of(
                new BankStatement.Entry("wrong-credit", BankStatement.Kind.PSP_RECEIPT, receipt, 9700, bookedAt),
                new BankStatement.Entry("wrong-debit", BankStatement.Kind.PAYOUT_DEBIT, payout.id(), -9700, bookedAt)), null), "finance");
        assertThat(imported.reconciliation().difference()).isZero();
        assertThat(imported.reconciliation().matched()).isFalse();
        assertThat(imported.reconciliation().issues()).hasSize(2);
        assertThat(count("SELECT count(*) FROM treasury_exceptions WHERE type = 'ESCROW_DIFFERENCE' AND status = 'OPEN'")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE escrow_bank_statements SET closing_balance = 1 WHERE id = :id")
            .param("id", imported.statement().id()).update()).hasMessageContaining("append-only");
    }

        @Test
        void definiteRejectionReleasesPayableWithoutPretendingCashWasSent() {
        var payout = payouts.queue(merchant.id(), batch(10_000, 97), "finance");
        assertThat(payout.amount()).isEqualTo(9703);
        payouts.advance(payout.id());
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("rejected");
        assertThat(treasury.balance(merchant.id()).escrow()).isEqualTo(9800);
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isZero();
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(9703);
        payouts.advance(payout.id());
        assertThat(remote.createCalls(payout.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE type = 'PAYOUT_SENT'")).isZero();
        }

        @Test
        void databaseCannotRewriteDispatchOrInventASuccessWithoutLedgerProof() {
        var payout = payouts.queue(merchant.id(), batch(10_000, 0), "finance");
        tx.executeWithoutResult(status -> {
            treasury.lock();
            repository.dispatch(payout.id(), clock.instant(), clock.instant().plusSeconds(1));
        });
        assertThatThrownBy(() -> jdbc.sql("UPDATE payout_instructions SET dispatched_at = dispatched_at + interval '1 second' WHERE id = :id")
            .param("id", payout.id()).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE payout_instructions SET status = 'SUCCEEDED' WHERE id = :id")
            .param("id", payout.id()).update()).hasStackTraceContaining("authoritative ledger posting");
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("pending");
        assertThatThrownBy(() -> payouts.get(owner.id(), payout.id())).hasMessageContaining("not found");
        }

        @Test
        void prefundingHasItsOwnProtectedAccountAndCannotBeBookedTwiceFromCopiedEvidence() {
        var from = clock.instant().minusSeconds(1);
        var booked = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        var input = new BankStatement(null, "prefund-bank", "primary-escrow", from, clock.instant(), 0, 1000,
            List.of(new BankStatement.Entry("prefund-entry", BankStatement.Kind.UNMATCHED, "capital", 1000, booked)), null);
        var statement = bank.ingest(input, "finance").statement();
        var command = new EscrowAdjustmentService.Command("prefund", EscrowAdjustmentService.Kind.PREFUNDING, 1000, statement.id(),
            "prefund-entry", "verified platform prefunding");
        var adjustment = adjustments.request(command, "maker");
        adjustments.decide(adjustment.id(), true, "checker");
        assertThat(treasury.balance(owner.id()).platformFunding()).isEqualTo(1000);
        assertThat(treasury.balance(owner.id()).platformFeeIncome()).isZero();
        assertThat(treasury.balance(null).merchantPayable()).isZero();
        assertThat(bank.reconcile(statement.id(), List.of()).matched()).isTrue();
        var copy = bank.ingest(new BankStatement(null, "copied-bank", "primary-escrow", input.from(), input.to(), 0, 1000, input.entries(), null), "finance");
        assertThat(copy.reconciliation().matched()).isTrue();
        assertThatThrownBy(() -> adjustments.request(new EscrowAdjustmentService.Command("duplicate-prefund", EscrowAdjustmentService.Kind.PREFUNDING,
            1000, copy.statement().id(), "prefund-entry", "same bank evidence"), "maker")).hasMessageContaining("duplicate key");
        assertThat(treasury.balance(owner.id()).platformFunding()).isEqualTo(1000);
        }

        private String batch(long amount, long reserve) {
        settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, reserve, false, "bank-registered", "agreement"), "finance");
        var from = clock.instant().minusSeconds(1);
        payAndSucceed(merchant, amount);
        clock.advance(Duration.ofDays(2));
        reconciliation.run(owner.id(), ALPHA, from, clock.instant());
        var batch = settlements.prepare(merchant.id(), "batch", "maker");
        settlements.decide(merchant.id(), batch.id(), true, "reviewed", "checker");
        return batch.id();
    }

        @Test
        void razorpayXAdapterCompletesTheApprovedBatchAgainstItsHttpContract() {
        try (var stub = new com.payments.gateway.support.StubPsp("/v1", 404, "{}")) {
            var settings = new com.payments.gateway.payout.razorpay.RazorpayPayoutProperties(true, stub.baseUrl(), "rzp_test_payout",
                "test-key-secret", "test-webhook-secret", "acc_payout", "7878780080316316", Duration.ofSeconds(1), Duration.ofSeconds(2));
            var adapter = new com.payments.gateway.payout.razorpay.RazorpayPayoutProvider(settings, json, new org.springframework.mock.env.MockEnvironment());
            doReturn("RAZORPAYX").when(properties).provider();
            doReturn(adapter).when(payoutProviders).require("RAZORPAYX");
            var queued = payouts.queue(merchant.id(), batch(10_000, 0), "finance");
            String response = json.write(Map.of("id", "pout_provider", "reference_id", queued.id(), "amount", 9800, "currency", "INR",
                "status", "processed", "debit_account_number", "7878780080316316", "fund_account", Map.of("id", "fa_test",
                    "account_type", "bank_account", "bank_account", Map.of("name", "Payout Merchant", "account_number", "123456789012", "ifsc", "HDFC0001234"))));
            stub.on("POST /v1/payouts", 200, response);
            stub.on("GET /v1/payouts/pout_provider", 200, response);
            payouts.advance(queued.id());
            assertThat(payouts.get(merchant.id(), queued.id()).status()).isEqualTo("succeeded");
            payouts.advance(queued.id());
            assertThat(stub.requests().stream().filter(request -> "POST".equals(request.method())).count()).isEqualTo(1);
            assertThat(stub.last("POST /v1/payouts").header("X-Payout-Idempotency")).isEqualTo(queued.id());
            assertThat(treasury.balance(merchant.id()).escrow()).isZero();
            assertThat(treasury.balance(null).difference()).isZero();
        }
        }

        @Test
        void wrongAmountAndDestinationStayReservedAndFailedDoesNotMeanReturned() {
        var payout = payouts.queue(merchant.id(), batch(10_000, 96), "finance");
        payouts.advance(payout.id());
        var original = remote.result(payout.id());
        var wrongAmount = new PayoutProvider.Result(Outcome.SUCCEEDED, original.providerReference(), payout.id(),
            com.payments.gateway.shared.model.Money.of(9703, "INR"), original.destination(), original.bankReference(), null);
        assertThat(payouts.apply(MockPayoutProvider.CODE, payout.id(), wrongAmount)).isFalse();
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isEqualTo(9704);
        var wrongBank = new PayoutProvider.Result(Outcome.SUCCEEDED, original.providerReference(), payout.id(), original.amount(),
            new PayoutProvider.Destination("Other", "999999999999", "HDFC0001234"), original.bankReference(), null);
        assertThat(payouts.apply(MockPayoutProvider.CODE, payout.id(), wrongBank)).isFalse();
        var failed = new PayoutProvider.Result(Outcome.FAILURE_UNCONFIRMED, original.providerReference(), payout.id(), original.amount(),
            original.destination(), original.bankReference(), "provider_failed");
        payouts.apply(MockPayoutProvider.CODE, payout.id(), failed);
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("review");
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isEqualTo(9704);
        payouts.apply(MockPayoutProvider.CODE, payout.id(), remote.move(payout.id(), Outcome.RETURNED));
        treasury.exceptions(merchant.id(), "open").forEach(exception -> treasury.resolveException(exception.id(), "finance", "review completed"));
        payouts.apply(MockPayoutProvider.CODE, payout.id(), failed);
        assertThat(treasury.exceptions(merchant.id(), "open")).isEmpty();
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("returned");
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(9704);
        }

        @Test
        void disablingNewDispatchKeepsAnOtherwiseEligibleQueueUnsent() {
        String batch = batch(10_000, 0);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        doReturn(false).when(properties).enabled();
        payouts.advance(payout.id());
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("queued");
        assertThat(payouts.get(merchant.id(), payout.id()).reason()).isEqualTo("dispatch_disabled");
        assertThat(payouts.get(merchant.id(), payout.id()).dispatchedAt()).isNull();
        assertThat(remote.createCalls(payout.id())).isZero();
        assertThat(payouts.queue(merchant.id(), batch, "finance").id()).isEqualTo(payout.id());
        doReturn(true).when(properties).enabled();
        payouts.advance(payout.id());
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("succeeded");
        }

        @Test
        void everyEvidenceIdentityIsCheckedIndependently() {
        var payout = payouts.queue(merchant.id(), batch(10_000, 96), "finance");
        payouts.advance(payout.id());
        var original = remote.result(payout.id());
        var success = new PayoutProvider.Result(Outcome.SUCCEEDED, original.providerReference(), payout.id(), original.amount(),
            original.destination(), original.bankReference(), null);
        assertThat(payouts.apply("OTHER_PROVIDER", payout.id(), success)).isFalse();
        var wrongReference = new PayoutProvider.Result(Outcome.SUCCEEDED, original.providerReference(), "pout_other", original.amount(),
            original.destination(), original.bankReference(), null);
        assertThat(payouts.apply(MockPayoutProvider.CODE, payout.id(), wrongReference)).isFalse();
        var wrongProviderId = new PayoutProvider.Result(Outcome.SUCCEEDED, "different_provider_id", payout.id(), original.amount(),
            original.destination(), original.bankReference(), null);
        assertThat(payouts.apply(MockPayoutProvider.CODE, payout.id(), wrongProviderId)).isFalse();
        var wrongIfsc = new PayoutProvider.Result(Outcome.SUCCEEDED, original.providerReference(), payout.id(), original.amount(),
            new PayoutProvider.Destination(original.destination().beneficiary(), original.destination().accountNumber(), "ICIC0001234"),
            original.bankReference(), null);
        assertThat(payouts.apply(MockPayoutProvider.CODE, payout.id(), wrongIfsc)).isFalse();
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isEqualTo(9704);
        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE type = 'PAYOUT_SENT'")).isZero();
        assertThat(payouts.apply(MockPayoutProvider.CODE, payout.id(), success)).isTrue();
        }

        @Test
        void returnMonitoringStartsAtDispatchEvenAfterALongQueueHold() {
        var payout = payouts.queue(merchant.id(), batch(10_000, 0), "finance");
        clock.advance(Duration.ofDays(31));
        payouts.advance(payout.id());
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("succeeded");
        assertThat(repository.find(payout.id(), false).orElseThrow().nextCheckAt()).isEqualTo(clock.instant().plus(Duration.ofDays(1)));
        clock.advance(Duration.ofDays(29));
        payouts.advance(payout.id());
        assertThat(repository.find(payout.id(), false).orElseThrow().nextCheckAt()).isEqualTo(clock.instant().plus(Duration.ofDays(1)));
        clock.advance(Duration.ofDays(1));
        payouts.advance(payout.id());
        assertThat(repository.find(payout.id(), false).orElseThrow().nextCheckAt()).isNull();
        assertThat(remote.createCalls(payout.id())).isEqualTo(1);
        }

        @Test
        void concurrentWorkersCommitOneSendPermissionAndOneRemoteCreate() throws Exception {
        String batch = batch(10_000, 0);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        List<Future<?>> pending = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            tx.executeWithoutResult(status -> {
                treasury.lock();
                for (int worker = 0; worker < 3; worker++) {
                    pending.add(executor.submit(() -> payouts.advance(payout.id())));
                }
                await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """).query(Long.class).single() >= 3);
            });
            for (Future<?> work : pending) {
                work.get(15, TimeUnit.SECONDS);
            }
        }
        payouts.check(merchant.id(), payout.id());
        assertThat(remote.createCalls(payout.id())).isEqualTo(1);
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("succeeded");
        assertThat(count("SELECT count(*) FROM payout_transitions WHERE reason = 'dispatch_started'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE type = 'PAYOUT_SENT'")).isEqualTo(1);
    }

    @Test
    void databaseFailureAfterRemoteSuccessRecoversWithoutAnotherCreate() {
        String batch = batch(10_000, 0);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        PayoutRepository target = AopTestUtils.getUltimateTargetObject(repository);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.getAndSet(false)) {
                throw new IllegalStateException("injected database failure after remote success");
            }
            return invocation.callRealMethod();
        }).when(target).update(any(PayoutRecord.class), eq(PayoutState.Status.SUCCEEDED), anyString(), anyString(), isNull(),
                any(java.time.Instant.class), any(java.time.Instant.class));
        assertThatThrownBy(() -> payouts.advance(payout.id())).hasMessageContaining("injected database failure");
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("pending");
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isEqualTo(9800);
        assertThat(remote.createCalls(payout.id())).isEqualTo(1);
        payouts.advance(payout.id());
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("succeeded");
        assertThat(remote.createCalls(payout.id())).isEqualTo(1);
        assertThat(treasury.balance(merchant.id()).escrow()).isZero();
    }

    @Test
    void currentMatchedBankEvidenceIsRequiredWhenTheDispatchGuardIsEnabled() {
        doReturn(true).when(escrow).requireCurrentStatement();
        var from = clock.instant().minusSeconds(1);
        String batch = batch(10_000, 0);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        payouts.advance(payout.id());
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("queued");
        String receipt = jdbc.sql("SELECT id FROM escrow_receipts").query(String.class).single();
        var booked = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        var imported = bank.ingest(new BankStatement(null, "before-dispatch", "primary-escrow", from, clock.instant(), 0, 9800,
                List.of(new BankStatement.Entry("receipt", BankStatement.Kind.PSP_RECEIPT, receipt, 9800, booked)), null), "finance");
        assertThat(imported.reconciliation().matched()).isTrue();
        payouts.advance(payout.id());
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("succeeded");
        assertThat(remote.createCalls(payout.id())).isEqualTo(1);
    }

    @Test
    void snapshotRotationPreservesTheInstructionAndTamperingCannotChangeItsAmount() {
        String batch = batch(10_000, 0);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        PayoutRecord record = repository.find(payout.id(), false).orElseThrow();
        assertThat(json.write(payout)).doesNotContain("123456789012", "Payout Merchant");
        String encoded = cipher.decrypt(record.instructionEncrypted(), PayoutRepository.context(record.id()));
        byte[] rotated = cipher.encrypt(encoded, PayoutRepository.context(record.id()));
        assertThat(repository.swapKeyRingCiphertext(record.id(), record.instructionEncrypted(), rotated)).isTrue();
        assertThat(payouts.get(merchant.id(), payout.id()).amount()).isEqualTo(9800);
        byte[] changed = cipher.encrypt(encoded.replace("9800", "9900"), PayoutRepository.context(record.id()));
        assertThat(repository.swapKeyRingCiphertext(record.id(), rotated, changed)).isTrue();
        assertThatThrownBy(() -> payouts.advance(payout.id())).hasMessageContaining("fingerprint mismatch");
        assertThat(remote.createCalls(payout.id())).isZero();
        assertThatThrownBy(() -> jdbc.sql("UPDATE payout_instructions SET amount = amount + 1 WHERE id = :id")
                .param("id", payout.id()).update()).hasMessageContaining("immutable");
    }

    @Test
    void disablingNewDispatchDoesNotDisableRecoveryOfAnExistingInstruction() {
        String batch = batch(10_000, 99);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        payouts.advance(payout.id());
        doReturn(false).when(properties).enabled();
        payouts.advance(payout.id());
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("succeeded");
        assertThat(remote.createCalls(payout.id())).isEqualTo(1);
    }

    @Test
    void unresolvedGaugeCountsPayoutsEvenAfterTheirExceptionWasAlreadyOpened() {
        var payout = payouts.queue(merchant.id(), batch(10_000, 96), "finance");
        payouts.advance(payout.id());
        clock.advance(Duration.ofMinutes(31));
        monitor.run();
        assertThat(meters.get("pg.payouts.unresolved").gauge().value()).isEqualTo(1);
        assertThat(repository.overdue(clock.instant().minus(properties.reviewAfter()))).isEmpty();
        monitor.run();
        assertThat(meters.get("pg.payouts.unresolved").gauge().value()).isEqualTo(1);
        assertThat(treasury.exceptions(merchant.id(), "open")).filteredOn(item -> item.type().equals("PAYOUT_UNRESOLVED")).hasSize(1);
    }

    @Test
    void bankMismatchSurvivesAcknowledgementAndFreshEvidenceEventuallyBecomesStale() {
        var from = clock.instant().minusSeconds(2);
        var bad = bank.ingest(new BankStatement(null, "unexplained-bank", "primary-escrow", from, clock.instant(), 0, 0, List.of(
                new BankStatement.Entry("unexplained-credit", BankStatement.Kind.UNMATCHED, "credit", 100, from),
                new BankStatement.Entry("unexplained-debit", BankStatement.Kind.UNMATCHED, "debit", -100, from)), null), "finance");
        assertThat(treasury.balance(null).difference()).isZero();
        monitor.run();
        assertThat(meters.get("pg.escrow.discrepancy").gauge().value()).isEqualTo(1);
        treasury.exceptions(null, "open").forEach(item -> treasury.resolveException(item.id(), "finance", "investigation acknowledged"));
        monitor.run();
        assertThat(meters.get("pg.escrow.discrepancy").gauge().value()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        var good = bank.ingest(new BankStatement(null, "current-bank", "primary-escrow", from, clock.instant(), 0, 0, List.of(), null), "finance");
        assertThat(good.reconciliation().matched()).isTrue();
        bank.reconcile(bad.statement().id(), List.of());
        monitor.run();
        assertThat(meters.get("pg.escrow.discrepancy").gauge().value()).isZero();
        assertThat(meters.get("pg.escrow.statement.missing").gauge().value()).isZero();
        clock.advance(Duration.ofHours(37));
        monitor.run();
        assertThat(meters.get("pg.escrow.statement.missing").gauge().value()).isEqualTo(1);
    }

    @Test
    void monitoringRaisesDebtAndUnresolvedPayoutsAndAcknowledgementDoesNotChangeMoney() {
        String batch = batch(10_000, 0);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        payouts.advance(payout.id());
        String payment = jdbc.sql("SELECT id FROM payments WHERE merchant_id = :merchant").param("merchant", merchant.id()).query(String.class).single();
        assertThat(post(merchant, "/v1/payments/" + payment + "/refunds", "post-payout-refund", Map.of("amount", 1000)).status()).isEqualTo(201);
        monitor.run();
        assertThat(meters.get("pg.escrow.negative.merchants").gauge().value()).isEqualTo(1);
        assertThat(meters.get("pg.escrow.statement.missing").gauge().value()).isEqualTo(1);
        var exception = treasury.exceptions(merchant.id(), "open").stream().filter(item -> item.type().equals("NEGATIVE_MERCHANT_BALANCE")).findFirst().orElseThrow();
        var before = treasury.balance(merchant.id());
        treasury.resolveException(exception.id(), "finance", "recovery arranged");
        assertThat(treasury.balance(merchant.id())).isEqualTo(before);
        monitor.run();
        assertThat(treasury.exceptions(merchant.id(), "open")).anyMatch(item -> item.type().equals("NEGATIVE_MERCHANT_BALANCE"));
        assertThat(admin("GET", "/admin/v1/treasury/exceptions", null).status()).isEqualTo(200);
    }

    @Test
    void aKnownReceiptInTransitClearsOnTheNextStatementButCannotBeReusedLater() {
        var from = clock.instant().minusSeconds(1);
        batch(10_000, 0);
        String receipt = jdbc.sql("SELECT id FROM escrow_receipts").query(String.class).single();
        clock.advance(Duration.ofSeconds(1));
        var firstEnd = clock.instant();
        var first = bank.ingest(new BankStatement(null, "transit-day-one", "primary-escrow", from, firstEnd, 0, 0, List.of(), null), "finance");
        var inTransit = new BankReconciliationService.Transit(BankStatement.Kind.PSP_RECEIPT, receipt);
        assertThat(bank.reconcile(first.statement().id(), List.of(inTransit)).matched()).isTrue();
        clock.advance(Duration.ofDays(1));
        var second = bank.ingest(new BankStatement(null, "transit-day-two", "primary-escrow", firstEnd, clock.instant(), 0, 9800,
                List.of(new BankStatement.Entry("late-bank-credit", BankStatement.Kind.PSP_RECEIPT, receipt, 9800, firstEnd.plusSeconds(1))), null), "finance");
        assertThat(second.reconciliation().matched()).as(second.reconciliation().issues().toString()).isTrue();
        var secondEnd = clock.instant();
        clock.advance(Duration.ofDays(1));
        var third = bank.ingest(new BankStatement(null, "transit-day-three", "primary-escrow", secondEnd, clock.instant(), 9800, 9800, List.of(), null), "finance");
        assertThat(third.reconciliation().matched()).isTrue();
        assertThat(bank.reconcile(third.statement().id(), List.of(inTransit)).issues()).contains("invalid_in_transit:" + receipt);
    }

    @Test
    void bankDebitBeforeDelayedProviderSuccessBecomesAnIdentifiedTimingDifference() {
        var from = clock.instant().minusSeconds(1);
        String batch = batch(10_000, 96);
        var payout = payouts.queue(merchant.id(), batch, "finance");
        payouts.advance(payout.id());
        String receipt = jdbc.sql("SELECT id FROM escrow_receipts").query(String.class).single();
        var booked = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        var statement = bank.ingest(new BankStatement(null, "bank-ahead", "primary-escrow", from, clock.instant(), 0, 96, List.of(
                new BankStatement.Entry("credit", BankStatement.Kind.PSP_RECEIPT, receipt, 9800, booked),
                new BankStatement.Entry("debit", BankStatement.Kind.PAYOUT_DEBIT, payout.id(), -9704, booked)), null), "finance");
        assertThat(statement.reconciliation().matched()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        remote.move(payout.id(), Outcome.SUCCEEDED);
        payouts.check(merchant.id(), payout.id());
        var reconciled = bank.reconcile(statement.statement().id(), List.of());
        assertThat(reconciled.matched()).as(reconciled.issues().toString()).isTrue();
        assertThat(reconciled.transitAmount()).isEqualTo(9704);
        assertThat(treasury.balance(merchant.id()).escrow()).isEqualTo(96);
    }

        @Test
        void databaseFreezesProviderIdentityAndRequiresAnActualReturnPosting() {
        var payout = payouts.queue(merchant.id(), batch(10_000, 0), "finance");
        payouts.advance(payout.id());
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("succeeded");
        assertThatThrownBy(() -> jdbc.sql("UPDATE payout_instructions SET provider_reference = 'another-provider-reference' WHERE id = :id")
            .param("id", payout.id()).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE payout_instructions SET status = 'RETURNED' WHERE id = :id")
            .param("id", payout.id()).update()).hasStackTraceContaining("authoritative ledger posting");
        assertThat(payouts.get(merchant.id(), payout.id()).status()).isEqualTo("succeeded");
        assertThat(treasury.balance(merchant.id()).escrow()).isZero();
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isZero();
        }

            @Test
            void databaseIndependentlyChecksCorrectionReviewerEvidenceAndLedgerProof() {
            var booked = clock.instant();
            clock.advance(Duration.ofSeconds(1));
            var statement = bank.ingest(new BankStatement(null, "correction-guards", "primary-escrow", booked, clock.instant(), 0, 3000, List.of(
                new BankStatement.Entry("principal", BankStatement.Kind.UNMATCHED, "funding-one", 1000, booked),
                new BankStatement.Entry("second-principal", BankStatement.Kind.UNMATCHED, "funding-two", 2000, booked)), null), "finance").statement();
            var requested = adjustments.request(new EscrowAdjustmentService.Command("guarded-correction", EscrowAdjustmentService.Kind.PREFUNDING,
                1000, statement.id(), "principal", "verified funding"), "maker");
            assertThatThrownBy(() -> jdbc.sql("""
                UPDATE escrow_adjustments SET status = 'APPROVED', decided_by = 'checker', decided_at = :now WHERE id = :id
                """).param("id", requested.id()).param("now", Sql.ts(clock.instant())).update())
                .hasStackTraceContaining("authoritative ledger posting");
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                treasury.lock();
                Money amount = Money.of(1000, "INR");
                ledger.post(new Posting(owner.id(), TreasuryService.BOOK, LedgerTransactionType.ADJUSTMENT, "ESCROW_ADJUSTMENT", requested.id(),
                    "valid posting but invalid reviewer", booked, List.of(Leg.debit(LedgerAccountType.ESCROW, amount),
                    Leg.credit(LedgerAccountType.PLATFORM_FUNDING, amount))));
                jdbc.sql("UPDATE escrow_adjustments SET status = 'APPROVED', decided_by = 'maker', decided_at = :now WHERE id = :id")
                    .param("id", requested.id()).param("now", Sql.ts(clock.instant())).update();
            })).hasStackTraceContaining("ck_escrow_adjustment_checker");
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                treasury.lock();
                jdbc.sql("""
                    INSERT INTO escrow_adjustments (id, reference, owner_id, kind, amount, statement_id, bank_entry_id,
                    reason, status, created_by, created_at)
                    VALUES ('eadj_wrong_amount', 'wrong-bank-amount', :owner, 'PREFUNDING', 2001, :statement, 'second-principal',
                    'mismatched evidence', 'PENDING', 'maker', :now)
                    """).param("owner", owner.id()).param("statement", statement.id()).param("now", Sql.ts(clock.instant())).update();
                Money amount = Money.of(2001, "INR");
                ledger.post(new Posting(owner.id(), TreasuryService.BOOK, LedgerTransactionType.ADJUSTMENT, "ESCROW_ADJUSTMENT", "eadj_wrong_amount",
                    "posting matches correction but not bank evidence", booked, List.of(Leg.debit(LedgerAccountType.ESCROW, amount),
                    Leg.credit(LedgerAccountType.PLATFORM_FUNDING, amount))));
                jdbc.sql("UPDATE escrow_adjustments SET status = 'APPROVED', decided_by = 'checker', decided_at = :now WHERE id = 'eadj_wrong_amount'")
                    .param("now", Sql.ts(clock.instant())).update();
            })).hasStackTraceContaining("correction must match its bank evidence");
            assertThat(treasury.balance(null).escrow()).isZero();
            assertThat(adjustments.get(requested.id()).status()).isEqualTo("PENDING");
            assertThat(count("SELECT count(*) FROM escrow_adjustments")).isEqualTo(1);
            }

            @Test
            void bankFeeNeedsIndependentEvidenceBasedCorrectionAndCannotRewriteStatement() {
        var from = clock.instant().minusSeconds(1);
        batch(10_000, 0);
        String receipt = jdbc.sql("SELECT id FROM escrow_receipts").query(String.class).single();
        var bookedAt = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        var imported = bank.ingest(new BankStatement(null, "bank-with-fee", "primary-escrow", from, clock.instant(), 0, 9700, List.of(
                new BankStatement.Entry("receipt", BankStatement.Kind.PSP_RECEIPT, receipt, 9800, bookedAt),
                new BankStatement.Entry("bank-fee", BankStatement.Kind.UNMATCHED, "fee-reference", -100, bookedAt)), null), "finance");
        assertThat(imported.reconciliation().difference()).isEqualTo(-100);
        assertThatThrownBy(() -> adjustments.request(new EscrowAdjustmentService.Command("wrong-fee-amount", EscrowAdjustmentService.Kind.BANK_FEE,
            101, imported.statement().id(), "bank-fee", "confirmed provider fee debit"), "maker")).hasMessageContaining("same signed amount");
        assertThatThrownBy(() -> adjustments.request(new EscrowAdjustmentService.Command("not-unmatched", EscrowAdjustmentService.Kind.PREFUNDING,
            9800, imported.statement().id(), "receipt", "already a PSP receipt"), "maker")).hasMessageContaining("unmatched bank entry");
        var command = new EscrowAdjustmentService.Command("fee-correction", EscrowAdjustmentService.Kind.BANK_FEE, 100,
                imported.statement().id(), "bank-fee", "confirmed provider fee debit");
        var requested = adjustments.request(command, "maker");
        assertThat(adjustments.request(command, "maker").id()).isEqualTo(requested.id());
        assertThatThrownBy(() -> adjustments.request(new EscrowAdjustmentService.Command(command.reference(), EscrowAdjustmentService.Kind.PREFUNDING,
            command.amount(), command.statementId(), command.bankEntryId(), command.reason()), "maker")).hasMessageContaining("reused with different evidence");
        assertThatThrownBy(() -> adjustments.request(new EscrowAdjustmentService.Command(command.reference(), command.kind(),
            101, command.statementId(), command.bankEntryId(), command.reason()), "maker")).hasMessageContaining("reused with different evidence");
        assertThatThrownBy(() -> adjustments.decide(requested.id(), true, "maker")).hasMessageContaining("second operator");
        adjustments.decide(requested.id(), true, "checker");
        adjustments.decide(requested.id(), true, "checker");
        assertThat(treasury.balance(owner.id()).platformFeeIncome()).isEqualTo(-100);
        assertThat(treasury.balance(null).escrow()).isEqualTo(9700);
        assertThat(treasury.balance(merchant.id()).settlementPayable()).isEqualTo(9800);
        assertThat(bank.reconcile(imported.statement().id(), List.of()).matched()).isTrue();
        assertThat(bank.get(imported.statement().id()).closingBalance()).isEqualTo(9700);
        assertThat(treasury.balance(null).difference()).isZero();
    }
}