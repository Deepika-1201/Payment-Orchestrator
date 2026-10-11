package com.payments.gateway.treasury;

import com.payments.gateway.merchant.AggregationProperties;
import com.payments.gateway.merchant.KycDetails;
import com.payments.gateway.merchant.OnboardingService;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.billing.BillingService;
import com.payments.gateway.billing.BillingAmounts;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.reconciliation.ReconciliationService;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.ledger.LedgerModel;
import com.payments.gateway.ledger.LedgerAccountType;
import com.payments.gateway.ledger.LedgerTransactionType;
import com.payments.gateway.shared.events.CurrencyConversion;
import com.payments.gateway.shared.events.FundsMovement;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.test.util.AopTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"pg.aggregation.enabled=true", "pg.aggregation.platform-merchant-id=treasury-owner",
        "pg.aggregation.collections-enabled=true"})
class TreasuryIntegrationTest extends IntegrationTest {
    @Autowired
    private OnboardingService onboarding;
    @Autowired
    private TreasuryService treasury;
        @MockitoSpyBean
    private TreasuryRepository repository;
    @Autowired
    private TransactionTemplate tx;
        @Autowired
        private BillingService billing;
        @Autowired
        private ReconciliationService reconciliation;
        @Autowired
        private SettlementService settlements;
        @Autowired
        private SettlementJob job;
        @Autowired
        private LedgerService ledger;
        @Autowired
        private MerchantDirectory directory;
        @MockitoSpyBean
        private ProviderClient reportClient;
    @MockitoSpyBean
    private AggregationProperties properties;

    private TestMerchant platform;
    private TestMerchant merchant;

    @BeforeEach
    void enroll() {
        platform = createMerchant(ALPHA);
        doReturn(platform.id()).when(properties).platformMerchantId();
                merchant = aggregator();
        }

        private KycDetails details() {
                return new KycDetails("Treasury Merchant", "ABCDE1234F", "27ABCDE1234F1Z5",
                new KycDetails.BankAccount("123456789012", "HDFC0001234", "Treasury Merchant"),
                List.of(new KycDetails.BeneficialOwner("Owner", "BCDEF2345G", 10_000)), KycDetails.Risk.LOW,
                new KycDetails.Verification("kyc-verified", "background-verified", "bank-verified", clock.instant()),
                Map.of("INR", new KycDetails.Limits(100_000_000, 1_000_000_000), "USD", new KycDetails.Limits(100_000, 1_000_000)));
        }

        private TestMerchant aggregator() {
                TestMerchant created = createMerchant();
                var submission = onboarding.submit(created.id(), details(), "maker");
                onboarding.review(created.id(), submission.id(), true, "checker");
                return created;
    }

    @Test
    void captureAndRefundCreateReceivablesNotEscrowCash() {
        String paymentId = str(payAndSucceed(merchant, 10_000), "id");
        var captured = treasury.balance(merchant.id());
        assertThat(captured.escrow()).isZero();
        assertThat(captured.pspReceivable()).isEqualTo(10_000);
        assertThat(captured.merchantPayable()).isEqualTo(10_000);
        assertThat(captured.difference()).isZero();
        Response refund = post(merchant, "/v1/payments/" + paymentId + "/refunds", "refund", Map.of("amount", 3000));
        assertThat(refund.status()).as(refund.raw()).isEqualTo(201);
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(7000);
        assertThat(treasury.balance(merchant.id()).pspReceivable()).isEqualTo(7000);
        assertThat(TreasuryAmounts.total(repository.sources(merchant.id()).stream().map(TreasuryModel.Source::line).toList()).net())
                .isEqualTo(7000);
        assertThat(treasury.balance(null).difference()).isZero();
        assertThat(ledgerBalances(merchant)).doesNotContainKey("sales_clearing");
    }

    @Test
    void lateCapturesRemainCustomerFundsAndTheirReturnsDoNotDebitMerchantPayable() {
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
        Response confirmation = confirm(merchant, paymentId, upi("intent"));
        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireDue();
        simulate(ALPHA, str(confirmation.body(), "latest_attempt.provider_reference"), "success", false);
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isZero();
        assertThat(treasury.balance(merchant.id()).customerFunds()).isZero();
        assertThat(treasury.balance(merchant.id()).pspReceivable()).isZero();
        assertThat(repository.sources(merchant.id())).isEmpty();
        assertThat(treasury.balance(null).difference()).isZero();
    }

    @Test
    void conversionsAreOrderIndependentAndUseActualInrRatherThanCarriedValue() {
        FundsMovement capture = new FundsMovement(FundsMovement.Type.CAPTURE, merchant.id(), ALPHA, "foreign-capture", "foreign-payment",
                new Money(1000, "USD"), clock.instant(), true);
        CurrencyConversion converted = new CurrencyConversion("fx-capture", CurrencyConversion.Kind.CAPTURE, merchant.id(), ALPHA,
                new Money(85_000, "INR"), 85_000, clock.instant(), capture.referenceId());
        tx.executeWithoutResult(status -> treasury.record(converted));
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isZero();
        tx.executeWithoutResult(status -> treasury.record(capture));
        FundsMovement refund = new FundsMovement(FundsMovement.Type.REFUND, merchant.id(), ALPHA, "foreign-refund", "foreign-payment",
                new Money(500, "USD"), clock.instant(), true);
        tx.executeWithoutResult(status -> treasury.record(refund));
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(85_000);
        CurrencyConversion refunded = new CurrencyConversion("fx-refund", CurrencyConversion.Kind.REFUND, merchant.id(), ALPHA,
                new Money(43_000, "INR"), 42_500, clock.instant(), refund.referenceId());
        tx.executeWithoutResult(status -> treasury.record(refunded));
        tx.executeWithoutResult(status -> {
            treasury.record(capture);
            treasury.record(converted);
            treasury.record(refund);
            treasury.record(refunded);
        });
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(42_000);
        assertThat(repository.sources(merchant.id())).hasSize(2);
        assertThat(treasury.balance(null).difference()).isZero();
    }

    @Test
    void journalAndLedgerRollBackTogetherAndRejectConflictingReplays() {
        FundsMovement movement = new FundsMovement(FundsMovement.Type.CAPTURE, merchant.id(), ALPHA, "capture", "payment",
                new Money(1000, "INR"), clock.instant(), true);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            treasury.record(movement);
            throw new IllegalStateException("rollback probe");
        })).hasMessageContaining("rollback probe");
        assertThat(repository.sources(merchant.id())).isEmpty();
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isZero();
        tx.executeWithoutResult(status -> treasury.record(movement));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> treasury.record(new FundsMovement(movement.type(), merchant.id(), ALPHA,
                movement.referenceId(), movement.paymentId(), new Money(2000, "INR"), movement.occurredAt(), true))))
                .hasMessageContaining("Conflicting treasury movement");
        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(1000);
    }

        @Test
        void invoiceAndCreditNoteDeductTheirExactFrozenTotalsOnce() {
                YearMonth month = YearMonth.from(clock.instant().atZone(SettlementCalendar.ZONE));
                billing.publish(merchant.id(), new BillingService.PublishPlan("Gateway fees", null,
                                Map.of(MethodType.UPI, new BillingAmounts.Price(100, 50)), Map.of(), 0), "finance");
                payAndSucceed(merchant, 10_000);
                assertThat(treasury.balance(merchant.id()).platformFeeIncome()).isZero();
                clock.set(month.plusMonths(1).atDay(1).atStartOfDay(SettlementCalendar.ZONE).toInstant());
                var invoice = billing.issue(merchant.id(), month, "finance");
                assertThat(invoice.collectionMode()).isEqualTo("SETTLEMENT_DEDUCTION");
                assertThat(invoice.subtotal()).isEqualTo(150);
                assertThat(invoice.tax()).isEqualTo(27);
                assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(9823);
                assertThat(treasury.balance(merchant.id()).platformFeeIncome()).isEqualTo(150);
                assertThat(treasury.balance(merchant.id()).gstPayable()).isEqualTo(27);
                assertThat(billing.issue(merchant.id(), month, "finance")).isEqualTo(invoice);
                var command = new BillingService.CreateCredit("correction", "pricing correction",
                                List.of(new BillingService.CreditAmount(invoice.lines().getFirst().id(), 50)));
                var credit = billing.credit(merchant.id(), invoice.id(), command, "finance");
                assertThat(credit.tax()).isEqualTo(9);
                assertThat(billing.credit(merchant.id(), invoice.id(), command, "finance")).isEqualTo(credit);
                assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(9882);
                assertThat(treasury.balance(merchant.id()).platformFeeIncome()).isEqualTo(100);
                assertThat(treasury.balance(merchant.id()).gstPayable()).isEqualTo(18);
                assertThat(repository.sources(merchant.id())).hasSize(3);
                assertThat(repository.unclaimedNet(merchant.id(), clock.instant())).isEqualTo(9882);
                assertThat(TreasuryAmounts.total(repository.sources(merchant.id()).stream().map(TreasuryModel.Source::line).toList()).net())
                        .isEqualTo(9882);
                assertThat(treasury.balance(null).difference()).isZero();
        }

        @Test
        void pooledPspReportAllocatesCashOnceAndRejectsMismatchedPayouts() {
                var start = clock.instant().minusSeconds(1);
                payAndSucceed(merchant, 10_000);
                TestMerchant second = createMerchant();
                var details = new KycDetails("Second Merchant", "ABCDE1234F", "27ABCDE1234F1Z5",
                                new KycDetails.BankAccount("123456789013", "HDFC0001234", "Second Merchant"),
                                List.of(new KycDetails.BeneficialOwner("Owner", "BCDEF2345G", 10_000)), KycDetails.Risk.LOW,
                                new KycDetails.Verification("kyc", "background", "bank", clock.instant()),
                                Map.of("INR", new KycDetails.Limits(100_000_000, 1_000_000_000)));
                var submission = onboarding.submit(second.id(), details, "maker");
                onboarding.review(second.id(), submission.id(), true, "checker");
                payAndSucceed(second, 20_000);
                clock.advance(Duration.ofSeconds(1));
                var end = clock.instant();
                var provider = mockProviders.stream().filter(mock -> ALPHA.equals(mock.code())).findFirst().orElseThrow();
                provider.psp().shortSettlement(platform.id(), 100);
                var mismatch = reconciliation.run(platform.id(), ALPHA, start, end);
                assertThat(mismatch.exceptions()).anyMatch(exception -> "settlement_mismatch".equals(exception.type()));
                assertThat(treasury.balance(null).escrow()).isZero();
                provider.psp().clearAnomalies();
                var matched = reconciliation.run(platform.id(), ALPHA, start, end);
                assertThat(matched.linesMatched()).isEqualTo(2);
                assertThat(treasury.balance(merchant.id()).escrow()).isEqualTo(9800);
                assertThat(treasury.balance(second.id()).escrow()).isEqualTo(19_600);
                assertThat(treasury.balance(null).pspReceivable()).isZero();
                reconciliation.run(platform.id(), ALPHA, start, end);
                assertThat(treasury.balance(null).escrow()).isEqualTo(29_400);
                assertThat(count("SELECT count(*) FROM escrow_receipts")).isEqualTo(1);
                assertThat(treasury.balance(null).difference()).isZero();
                assertThatThrownBy(() -> reconciliation.run(merchant.id(), ALPHA, start, end)).hasMessageContaining("collection owner once");
        }

        @Test
        void aRefundOnlyPspSettlementDebitsEscrowWithoutInventingNegativeMoney() {
                var start = clock.instant().minusSeconds(1);
                String paymentId = str(payAndSucceed(merchant, 10_000), "id");
                clock.advance(Duration.ofSeconds(1));
                var firstEnd = clock.instant();
                reconciliation.run(platform.id(), ALPHA, start, firstEnd);
                clock.advance(Duration.ofSeconds(1));
                Response refund = post(merchant, "/v1/payments/" + paymentId + "/refunds", "later-refund", Map.of("amount", 1500));
                assertThat(refund.status()).as(refund.raw()).isEqualTo(201);
                assertThat(treasury.balance(merchant.id()).pspReceivable()).isEqualTo(-1500);
                clock.advance(Duration.ofSeconds(1));
                var report = reconciliation.run(platform.id(), ALPHA, firstEnd, clock.instant());
                assertThat(report.settledAmount()).isEqualTo(-1500);
                assertThat(treasury.balance(merchant.id()).escrow()).isEqualTo(8300);
                assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(8300);
                assertThat(treasury.balance(merchant.id()).pspReceivable()).isZero();
                assertThat(treasury.balance(null).difference()).isZero();
        }

            @Test
            void draftFreezesNetSourcesAndReserveThenRequiresIndependentApproval() {
                settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 1000, false, "bank-registered", "merchant agreement"), "finance");
                var start = clock.instant().minusSeconds(1);
                payAndSucceed(merchant, 10_000);
                assertThat(repository.unclaimed(merchant.id(), clock.instant())).isEmpty();
                assertThatThrownBy(() -> settlements.prepare(merchant.id(), "batch-1", "maker")).hasMessageContaining("No positive mature");
                clock.advance(Duration.ofDays(2));
                assertThatThrownBy(() -> settlements.prepare(merchant.id(), "batch-1", "maker")).hasMessageContaining("escrow cash");
                reconciliation.run(platform.id(), ALPHA, start, clock.instant());
                var batch = settlements.prepare(merchant.id(), "batch-1", "maker");
                assertThat(batch.amount()).isEqualTo(8800);
                assertThat(batch.totals().captures()).isEqualTo(10_000);
                assertThat(batch.totals().pspFees()).isEqualTo(200);
                assertThat(batch.totals().reserveChange()).isEqualTo(1000);
                assertThat(batch.items()).hasSize(3);
                assertThat(settlements.prepare(merchant.id(), "batch-1", "maker")).isEqualTo(batch);
                assertThat(treasury.balance(merchant.id()).merchantPayable()).isZero();
                assertThat(treasury.balance(merchant.id()).reserve()).isEqualTo(1000);
                assertThat(treasury.balance(merchant.id()).settlementPayable()).isEqualTo(8800);
                assertThatThrownBy(() -> settlements.decide(merchant.id(), batch.id(), true, "reviewed", "maker"))
                        .hasMessageContaining("second operator");
                var approved = settlements.decide(merchant.id(), batch.id(), true, "reviewed", "checker");
                assertThat(approved.status()).isEqualTo("APPROVED");
                assertThat(settlements.decide(merchant.id(), batch.id(), true, "reviewed", "checker")).isEqualTo(approved);
                assertThatThrownBy(() -> settlements.decide(merchant.id(), batch.id(), false, "undo", "checker"))
                        .hasMessageContaining("cannot be changed");
                assertThatThrownBy(() -> jdbc.sql("UPDATE settlement_batches SET amount = amount + 1 WHERE id = :id")
                        .param("id", batch.id()).update()).hasMessageContaining("immutable");
                assertThat(treasury.balance(null).difference()).isZero();
            }

            @Test
            void laterRefundUsesReserveAndPreventsApprovingAStaleBatch() {
                settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 1000, false, "bank-registered", "agreement"), "finance");
                var start = clock.instant().minusSeconds(1);
                String paymentId = str(payAndSucceed(merchant, 10_000), "id");
                clock.advance(Duration.ofDays(2));
                reconciliation.run(platform.id(), ALPHA, start, clock.instant());
                var batch = settlements.prepare(merchant.id(), "before-refund", "maker");
                Response refund = post(merchant, "/v1/payments/" + paymentId + "/refunds", "refund-after-draft", Map.of("amount", 2000));
                assertThat(refund.status()).as(refund.raw()).isEqualTo(201);
                assertThat(treasury.balance(merchant.id()).reserve()).isZero();
                assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(-1000);
                assertThatThrownBy(() -> settlements.decide(merchant.id(), batch.id(), true, "approve", "checker"))
                        .hasMessageContaining("New debits");
                var rejected = settlements.decide(merchant.id(), batch.id(), false, "refund reduced backing", "maker");
                assertThat(rejected.status()).isEqualTo("REJECTED");
                assertThat(rejected.items()).isEqualTo(batch.items());
                assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(7800);
                var replacement = settlements.prepare(merchant.id(), "after-refund", "maker");
                assertThat(replacement.amount()).isEqualTo(6800);
                assertThat(settlements.prepare(merchant.id(), "before-refund", "maker")).isEqualTo(rejected);
                assertThat(treasury.balance(null).difference()).isZero();
            }

        @Test
        void zeroNetReserveBatchAndLaterReserveOnlyReleaseNeedNoNewCapture() {
                settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 9800, false, "registered", "reserve agreement"), "finance");
                var start = clock.instant().minusSeconds(1);
                payAndSucceed(merchant, 10_000);
                clock.advance(Duration.ofDays(2));
                reconciliation.run(platform.id(), ALPHA, start, clock.instant());
                var reserved = settlements.prepare(merchant.id(), "reserve-all", "maker");
                assertThat(reserved.amount()).isZero();
                settlements.decide(merchant.id(), reserved.id(), true, "verified", "checker");
                assertThat(treasury.balance(merchant.id()).reserve()).isEqualTo(9800);
                clock.advance(Duration.ofSeconds(1));
                settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, false, "registered", "reserve released"), "finance");
                assertThat(repository.merchantsAfter("", 100, clock.instant())).contains(merchant.id());
                var released = settlements.prepare(merchant.id(), "release-reserve", "maker");
                assertThat(released.amount()).isEqualTo(9800);
                assertThat(released.totals().reserveChange()).isEqualTo(-9800);
                assertThat(released.items()).hasSize(1);
                assertThat(treasury.balance(merchant.id()).reserve()).isZero();
                assertThat(treasury.balance(null).difference()).isZero();
        }

        @Test
        void bankCreditFeeReplayDoesNotDependOnWhetherTheCreditWasLaterReturned() {
                var start = clock.instant().minusSeconds(1);
                String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
                Response confirmation = confirm(merchant, paymentId, Map.of("type", "bank_transfer"));
                Response credit = send("POST", "/simulator/" + ALPHA + "/collections/"
                                + str(confirmation.body(), "latest_attempt.provider_reference") + "/credits", Map.of(),
                                Map.of("amount", 5000, "mode", "neft", "webhook", true));
                assertThat(credit.status()).as(credit.raw()).isEqualTo(200);
                clock.advance(Duration.ofSeconds(1));
                var end = clock.instant();
                reconciliation.run(platform.id(), ALPHA, start, end);
                assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(-100);
                clock.advance(Duration.ofMinutes(16));
                expiryJob.expireDue();
                assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("expired");
                reconciliation.run(platform.id(), ALPHA, start, end);
                assertThat(count("SELECT count(*) FROM treasury_psp_fees")).isEqualTo(1);
                assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(-100);
                assertThat(treasury.balance(null).difference()).isZero();
        }

        @Test
        void apiDefaultsAndWorkerPrepareDraftsButNeverApproveThem() {
                String path = "/admin/v1/merchants/" + merchant.id();
                Response policy = admin("POST", path + "/settlement-policy",
                                Map.of("bank_registration_reference", "registered", "reason", "agreement"));
                assertThat(policy.status()).as(policy.raw()).isEqualTo(201);
                assertThat(str(policy.body(), "business_days")).isEqualTo("1");
                assertThat(str(policy.body(), "reserve_target")).isEqualTo("0");
                var start = clock.instant().minusSeconds(1);
                payAndSucceed(merchant, 10_000);
                clock.advance(Duration.ofDays(2));
                reconciliation.run(platform.id(), ALPHA, start, clock.instant());
                assertThat(job.run()).isEqualTo(1);
                var batch = settlements.list(merchant.id()).getFirst();
                assertThat(batch.status()).isEqualTo("DRAFT");
                assertThat(batch.createdBy()).isEqualTo("settlement-worker");
                assertThat(job.run()).isZero();
                assertThat(admin("GET", path + "/settlement-batches/" + batch.id(), null).status()).isEqualTo(200);
                assertThat(admin("GET", path + "/treasury", null).status()).isEqualTo(200);
                assertThat(admin("GET", "/admin/v1/treasury/escrow", null).status()).isEqualTo(200);
                assertThat(send("POST", path + "/settlement-batches/" + batch.id() + "/approve",
                                Map.of("Authorization", "Bearer test-readonly-token"), Map.of("reason", "approve")).status()).isEqualTo(403);
        }

        @Test
        void protectedCashKeepsGstCustomerMoneyReservesAndPendingDebitsSeparate() {
                ledger.post(new LedgerModel.Posting(merchant.id(), TreasuryService.BOOK, LedgerTransactionType.ADJUSTMENT,
                                "TEST", "protected", "protected balance fixture", clock.instant(), List.of(
                                LedgerModel.Leg.debit(LedgerAccountType.ESCROW, Money.of(5000, "INR")),
                                LedgerModel.Leg.credit(LedgerAccountType.RESERVE, Money.of(101, "INR")),
                                LedgerModel.Leg.credit(LedgerAccountType.SETTLEMENT_PAYABLE, Money.of(223, "INR")),
                                LedgerModel.Leg.credit(LedgerAccountType.CUSTOMER_FUNDS, Money.of(307, "INR")),
                                LedgerModel.Leg.credit(LedgerAccountType.PLATFORM_FEE_INCOME, Money.of(409, "INR")),
                                LedgerModel.Leg.credit(LedgerAccountType.GST_PAYABLE, Money.of(71, "INR")),
                                LedgerModel.Leg.credit(LedgerAccountType.MERCHANT_PAYABLE, Money.of(3889, "INR")))));
                assertThat(repository.protectedCash(merchant.id())).isEqualTo(1111);
                ledger.post(new LedgerModel.Posting(merchant.id(), TreasuryService.BOOK, LedgerTransactionType.REFUND_SUCCEEDED,
                                "TEST", "unsettled-refund", "pending PSP debit", clock.instant(), List.of(
                                LedgerModel.Leg.debit(LedgerAccountType.MERCHANT_PAYABLE, Money.of(73, "INR")),
                                LedgerModel.Leg.credit(LedgerAccountType.PSP_RECEIVABLE, Money.of(73, "INR")))));
                assertThat(repository.protectedCash(merchant.id())).isEqualTo(1184);
                ledger.post(new LedgerModel.Posting(merchant.id(), TreasuryService.BOOK, LedgerTransactionType.PSP_FEE,
                                "TEST", "platform-loss", "platform loss cannot consume GST", clock.instant(), List.of(
                                LedgerModel.Leg.debit(LedgerAccountType.PLATFORM_FEE_INCOME, Money.of(500, "INR")),
                                LedgerModel.Leg.credit(LedgerAccountType.PSP_RECEIVABLE, Money.of(500, "INR")))));
                assertThat(repository.protectedCash(merchant.id())).isEqualTo(1275);
                assertThat(repository.protectedCash(null)).isEqualTo(1275);
                assertThat(treasury.balance(null).difference()).isZero();
        }

        @Test
        void unmatchedOppositeRowsCannotHideBehindAMatchingPayoutTotal() {
                var start = clock.instant().minusSeconds(1);
                var payment = payAndSucceed(merchant, 10_000);
                clock.advance(Duration.ofSeconds(1));
                var end = clock.instant();
                Money zero = Money.of(0, "INR");
                var report = new SettlementReport(List.of(
                                new SettlementReport.Line("known", SettlementReport.LineType.PAYMENT,
                                                str(payment, "latest_attempt.provider_reference"), str(payment, "latest_attempt.id"),
                                                Money.of(10_000, "INR"), zero, "balanced-but-unmatched", start),
                                new SettlementReport.Line("unknown-credit", SettlementReport.LineType.PAYMENT, "unknown-credit", null,
                                                Money.of(1000, "INR"), zero, "balanced-but-unmatched", start),
                                new SettlementReport.Line("unknown-refund", SettlementReport.LineType.REFUND, "unknown-refund", null,
                                                Money.of(1000, "INR"), zero, "balanced-but-unmatched", start)),
                                List.of(new SettlementReport.Settlement("balanced-but-unmatched", 10_000, "INR", "UTR", end)));
                doReturn(report).when(reportClient).fetchSettlementReport(eq(ALPHA), any(SettlementReportQuery.class));
                var result = reconciliation.run(platform.id(), ALPHA, start, end);
                assertThat(result.exceptions()).anyMatch(exception -> "settlement_mismatch".equals(exception.type()));
                assertThat(treasury.balance(null).escrow()).isZero();
                assertThat(count("SELECT count(*) FROM escrow_receipts")).isZero();
                assertThat(directory.providerAccountsToReconcile(start)).extracting(MerchantDirectory.ProviderAccount::merchantId)
                                .containsExactly(platform.id());
                settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, false, "registered", "agreement"), "finance");
                clock.advance(Duration.ofDays(2));
                assertThatThrownBy(() -> settlements.prepare(merchant.id(), "held-by-report", "maker"))
                                .hasMessageContaining("Unresolved payment or reconciliation review");
        }

        @Test
        void databaseRequiresMatchingTotalsLedgerPostingsAndIndependentApproval() {
                settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, false, "registered", "agreement"), "finance");
                var start = clock.instant().minusSeconds(1);
                payAndSucceed(merchant, 10_000);
                clock.advance(Duration.ofDays(2));
                reconciliation.run(platform.id(), ALPHA, start, clock.instant());
                var original = settlements.prepare(merchant.id(), "original", "maker");
                  assertThatThrownBy(() -> jdbc.sql("""
                                    UPDATE settlement_batches SET status = 'APPROVED', decided_by = 'checker',
                                            decided_at = created_at, decision_reason = NULL WHERE id = :id
                                    """).param("id", original.id()).update()).hasMessageContaining("check constraint");
                assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE settlement_batches SET status = 'APPROVED', decided_by = created_by,
                                       decided_at = created_at, decision_reason = 'self approval' WHERE id = :id
                                """).param("id", original.id()).update()).hasMessageContaining("ck_settlement_checker");
                assertThatThrownBy(() -> jdbc.sql("DELETE FROM settlement_source_claims WHERE batch_id = :id")
                                .param("id", original.id()).update()).hasMessageContaining("only rejected drafts");
                settlements.decide(merchant.id(), original.id(), false, "rebuild", "checker");
                var totals = original.totals();
                var forgedTotals = new TreasuryAmounts.Totals(totals.captures() + 1, totals.refunds(), totals.chargebacks(),
                                totals.pspFees(), totals.serviceFees(), totals.gst(), totals.reserveChange(), totals.releasedPayouts());
                var forged = new TreasuryModel.Batch("sbat_forged", merchant.id(), "forged", original.policyId(), original.kycId(), "DRAFT",
                                forgedTotals, forgedTotals.net(), "maker", clock.instant(), null, null, null, original.items());
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                        repository.batch(forged);
                        Money amount = Money.of(forged.amount(), "INR");
                        ledger.post(new LedgerModel.Posting(merchant.id(), TreasuryService.BOOK, LedgerTransactionType.SETTLEMENT_RESERVED,
                                        "SETTLEMENT_BATCH", forged.id(), "independent total probe", clock.instant(),
                                        List.of(LedgerModel.Leg.debit(LedgerAccountType.MERCHANT_PAYABLE, amount),
                                                        LedgerModel.Leg.credit(LedgerAccountType.SETTLEMENT_PAYABLE, amount))));
                })).hasStackTraceContaining("totals, maturity and claims");
                var missingPosting = new TreasuryModel.Batch("sbat_missing", merchant.id(), "missing", original.policyId(), original.kycId(), "DRAFT",
                                totals, original.amount(), "maker", clock.instant(), null, null, null, original.items());
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.batch(missingPosting)))
                                .hasStackTraceContaining("authoritative ledger posting");
                assertThat(count("SELECT count(*) FROM settlement_batches")).isEqualTo(1);
                assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(9800);
                var receipt = new TreasuryModel.Receipt("erc_missing", platform.id(), ALPHA, "missing-ledger", 1000, "bank-ref", clock.instant(),
                                Map.of(merchant.id(), 1000L));
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.receipt(receipt)))
                                .hasStackTraceContaining("authoritative ledger posting");
        }

        @Test
        void randomizedAuthoritativeEventsPreserveIndependentBalancesAfterEveryCommit() {
                var random = new Random(44027);
                long receivable = 0;
                long payable = 0;
                long customer = 0;
                FundsMovement.Type[] types = FundsMovement.Type.values();
                for (int step = 0; step < 100; step++) {
                        FundsMovement.Type type = types[random.nextInt(types.length)];
                        long amount = random.nextLong(1, 10_000);
                        boolean entitled = random.nextBoolean();
                        FundsMovement movement = new FundsMovement(type, merchant.id(), ALPHA, "sequence-" + step, "payment-" + step,
                                        new Money(amount, "INR"), clock.instant(), entitled);
                        switch (type) {
                                case CAPTURE, CHARGEBACK_REVERSAL -> {
                                        receivable += amount;
                                        if (entitled) {
                                                payable += amount;
                                        } else {
                                                customer += amount;
                                        }
                                }
                                case REFUND, CHARGEBACK -> {
                                        receivable -= amount;
                                        if (entitled) {
                                                payable -= amount;
                                        } else {
                                                customer -= amount;
                                        }
                                }
                                case CREDIT_RECEIVED -> { receivable += amount; customer += amount; }
                                case CREDIT_APPLIED -> { customer -= amount; payable += amount; }
                                case CREDIT_RETURNED -> { customer -= amount; receivable -= amount; }
                        }
                        tx.executeWithoutResult(status -> treasury.record(movement));
                        tx.executeWithoutResult(status -> treasury.record(movement));
                        var balance = treasury.balance(merchant.id());
                        assertThat(balance.pspReceivable()).isEqualTo(receivable);
                        assertThat(balance.merchantPayable()).isEqualTo(payable);
                        assertThat(balance.customerFunds()).isEqualTo(customer);
                        assertThat(balance.escrow()).isZero();
                        assertThat(balance.difference()).isZero();
                }
        }

                                @Test
                                void holdsAndDestinationChangesPreventReleasingExistingDrafts() {
                                        settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, true, "registered", "review hold"), "finance");
                                        var start = clock.instant().minusSeconds(1);
                                        String paymentId = str(payAndSucceed(merchant, 10_000), "id");
                                        clock.advance(Duration.ofDays(2));
                                        reconciliation.run(platform.id(), ALPHA, start, clock.instant());
                                        assertThatThrownBy(() -> settlements.prepare(merchant.id(), "held", "maker")).hasMessageContaining("settlement is held");
                                        clock.advance(Duration.ofSeconds(1));
                                        settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, false, "registered", "hold cleared"), "finance");
                                        jdbc.sql("UPDATE merchants SET status = 'SUSPENDED', status_reason = 'review' WHERE id = :id").param("id", merchant.id()).update();
                                        assertThatThrownBy(() -> settlements.prepare(merchant.id(), "held", "maker")).hasMessageContaining("settlement is held");
                                        jdbc.sql("UPDATE merchants SET status = 'ACTIVE', status_reason = NULL WHERE id = :id").param("id", merchant.id()).update();
                                        jdbc.sql("UPDATE payment_attempts SET needs_review = true, review_reason = 'provider_conflict', flagged_at = now() WHERE payment_id = :id")
                                                        .param("id", paymentId).update();
                                        assertThatThrownBy(() -> settlements.prepare(merchant.id(), "held", "maker")).hasMessageContaining("Unresolved payment");
                                        jdbc.sql("UPDATE payment_attempts SET needs_review = false WHERE payment_id = :id").param("id", paymentId).update();
                                        var batch = settlements.prepare(merchant.id(), "held", "maker");
                                        clock.advance(Duration.ofSeconds(1));
                                        settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, false, "registered", "revised agreement"), "finance");
                                        assertThatThrownBy(() -> settlements.decide(merchant.id(), batch.id(), true, "approve", "checker"))
                                                        .hasMessageContaining("policy or verified destination changed");
                                        settlements.decide(merchant.id(), batch.id(), false, "policy changed", "maker");
                                        var updated = onboarding.submit(merchant.id(), details(), "maker");
                                        onboarding.review(merchant.id(), updated.id(), true, "checker");
                                        assertThatThrownBy(() -> settlements.prepare(merchant.id(), "new-destination", "maker"))
                                                        .hasMessageContaining("not been registered");
                                        clock.advance(Duration.ofSeconds(1));
                                        settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, false, "bank-re-registered", "new verified destination"), "finance");
                                        clock.advance(Duration.ofDays(731));
                                        assertThat(settlements.prepare(merchant.id(), "owed-after-expiry", "maker").amount()).isEqualTo(9800);
                                }

                                @Test
                                void differentMerchantsCannotConcurrentlyReserveTheSameEscrowCash() throws Exception {
                                        TestMerchant second = aggregator();
                                        TestMerchant debtor = aggregator();
                                        for (TestMerchant recipient : List.of(merchant, second)) {
                                                settlements.policy(recipient.id(), new SettlementService.PolicyCommand(1, 0, false, "registered", "agreement"), "finance");
                                        }
                                        var start = clock.instant().minusSeconds(1);
                                        payAndSucceed(merchant, 10_000);
                                        payAndSucceed(second, 10_000);
                                        clock.advance(Duration.ofDays(2));
                                        reconciliation.run(platform.id(), ALPHA, start, clock.instant());
                                        tx.executeWithoutResult(status -> treasury.record(new FundsMovement(FundsMovement.Type.CHARGEBACK, debtor.id(), ALPHA,
                                                        "prior-settlement-debt", "prior-payment", new Money(5000, "INR"), clock.instant(), true)));
                                        treasury.receive(platform.id(), ALPHA, "debt-debit", -5000, "INR", "BANK_DEBIT", clock.instant(), Map.of(debtor.id(), -5000L));
                                        assertThat(treasury.balance(null).escrow()).isEqualTo(14_600);
                                        List<Future<Object>> pending = new ArrayList<>();
                                        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                                                tx.executeWithoutResult(status -> {
                                                        repository.lock();
                                                        for (TestMerchant recipient : List.of(merchant, second)) {
                                                                pending.add(executor.submit(() -> {
                                                                        try {
                                                                                return settlements.prepare(recipient.id(), "concurrent", "maker");
                                                                        } catch (GatewayException failure) {
                                                                                return failure;
                                                                        }
                                                                }));
                                                        }
                                                        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                                                                        SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'
                                                                        """).query(Long.class).single() >= 2);
                                                });
                                                List<Object> results = List.of(pending.get(0).get(15, TimeUnit.SECONDS), pending.get(1).get(15, TimeUnit.SECONDS));
                                                assertThat(results.stream().filter(TreasuryModel.Batch.class::isInstance).count()).isEqualTo(1);
                                                assertThat(results.stream().filter(GatewayException.class::isInstance).map(GatewayException.class::cast)
                                                                .map(Throwable::getMessage).toList()).singleElement().asString().contains("escrow cash");
                                        }
                                        assertThat(treasury.balance(null).settlementPayable()).isEqualTo(9800);
                                        assertThat(treasury.balance(null).difference()).isZero();
                                }

                                @Test
                                void oneMerchantsCashCannotFundAnothersUnsettledReceivable() {
                                        TestMerchant funded = aggregator();
                                        settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, false, "registered", "agreement"), "finance");
                                        payAndSucceed(merchant, 10_000);
                                        payAndSucceed(funded, 20_000);
                                        clock.advance(Duration.ofDays(2));
                                        treasury.receive(platform.id(), ALPHA, "funded-merchant-only", 20_000, "INR", "BANK", clock.instant(), Map.of(funded.id(), 20_000L));
                                        assertThat(treasury.balance(null).escrow()).isEqualTo(20_000);
                                        assertThat(treasury.balance(merchant.id()).escrow()).isZero();
                                        assertThatThrownBy(() -> settlements.prepare(merchant.id(), "unfunded", "maker")).hasMessageContaining("escrow cash");
                                        assertThat(treasury.balance(null).settlementPayable()).isZero();
                                }

                                @Test
                                void invoiceFailureAfterTreasuryWritesRollsEverythingBack() {
                                        YearMonth month = YearMonth.from(clock.instant().atZone(SettlementCalendar.ZONE));
                                        billing.publish(merchant.id(), new BillingService.PublishPlan("Fees", null,
                                                        Map.of(MethodType.UPI, new BillingAmounts.Price(100, 0)), Map.of(), 0), "finance");
                                        payAndSucceed(merchant, 10_000);
                                        clock.set(month.plusMonths(1).atDay(1).atStartOfDay(SettlementCalendar.ZONE).toInstant());
                                        TreasuryRepository target = AopTestUtils.getUltimateTargetObject(repository);
                                        AtomicBoolean failOnce = new AtomicBoolean(true);
                                        doAnswer(invocation -> {
                                                Object result = invocation.callRealMethod();
                                                if (failOnce.getAndSet(false)) {
                                                        throw new IllegalStateException("Injected failure after treasury invoice source");
                                                }
                                                return result;
                                        }).when(target).source(anyString(), anyString(), eq(TreasuryAmounts.Kind.INVOICE), anyString(), anyLong(), anyLong(),
                                                        any(java.time.Instant.class), any(java.time.Instant.class));
                                        assertThatThrownBy(() -> billing.issue(merchant.id(), month, "finance")).hasMessageContaining("Injected failure");
                                        assertThat(count("SELECT count(*) FROM billing_invoices")).isZero();
                                        assertThat(count("SELECT count(*) FROM billing_series")).isZero();
                                        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(10_000);
                                        assertThat(treasury.balance(merchant.id()).platformFeeIncome()).isZero();
                                        assertThat(billing.issue(merchant.id(), month, "finance").number()).endsWith("000001");
                                        assertThat(treasury.balance(merchant.id()).merchantPayable()).isEqualTo(9882);
                                        assertThat(treasury.balance(null).difference()).isZero();
                                }

                                @Test
                                void workerPagesPastHeldMerchantsAndNeverReopensDecidedBatches() {
                                        TestMerchant second = aggregator();
                                        settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, true, "registered", "held"), "finance");
                                        settlements.policy(second.id(), new SettlementService.PolicyCommand(1, 0, false, "registered", "ready"), "finance");
                                        var start = clock.instant().minusSeconds(1);
                                        payAndSucceed(merchant, 10_000);
                                        payAndSucceed(second, 10_000);
                                        clock.advance(Duration.ofDays(2));
                                        reconciliation.run(platform.id(), ALPHA, start, clock.instant());
                                        var bounded = new SettlementJob(repository, settlements, new TreasuryProperties(null, 1), clock);
                                        assertThat(bounded.run()).isZero();
                                        assertThat(bounded.run()).isEqualTo(1);
                                        var batch = settlements.list(second.id()).getFirst();
                                        settlements.decide(second.id(), batch.id(), false, "manual hold", "checker");
                                        for (int cycle = 0; cycle < 4; cycle++) {
                                                assertThat(bounded.run()).isZero();
                                        }
                                        assertThat(settlements.list(second.id())).hasSize(1);
                                        assertThat(settlements.get(second.id(), batch.id()).status()).isEqualTo("REJECTED");
                                        clock.advance(Duration.ofSeconds(1));
                                        settlements.policy(merchant.id(), new SettlementService.PolicyCommand(1, 0, false, "registered", "hold cleared"), "finance");
                                        assertThat(bounded.run()).isEqualTo(1);
                                        assertThat(settlements.list(merchant.id())).hasSize(1);
                                }
}