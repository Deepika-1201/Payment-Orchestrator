package com.payments.gateway.payment;

import com.payments.gateway.ledger.LedgerAccountType;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.payment.application.PaymentConversionService;
import com.payments.gateway.payment.application.PaymentConversionService.Result;
import com.payments.gateway.payment.application.PaymentConversionService.Source;
import com.payments.gateway.payment.application.PaymentOutcomeService;
import com.payments.gateway.payment.application.RefundService;
import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.provider.mock.MockPsp;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.reconciliation.ReconciliationService;
import com.payments.gateway.shared.events.CurrencyConversion.Kind;
import com.payments.gateway.shared.model.Conversion;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

@TestPropertySource(properties = {"pg.risk.review-threshold=500", "pg.risk.block-threshold=900"})
class MultiCurrencyIntegrationTest extends IntegrationTest {

    @Autowired
    private PaymentConversionService conversions;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private PaymentOutcomeService outcomes;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private LedgerService ledger;
    @Autowired
    private RefundService refunds;
    @Autowired
    private ReconciliationService reconciliation;
    @MockitoSpyBean(name = "mockAlphaProvider")
    private MockPaymentProvider alpha;

    @Test
    void foreignPaymentsRequireMerchantOptInAndAnActiveCapableProvider() {
        TestMerchant merchant = createMerchant(ALPHA);
        assertThat(create(merchant, 10_000, "USD", "automatic").status()).isEqualTo(422);
        enable(merchant);
        assertThat(create(merchant, 10_000, "USD", "automatic").status()).isEqualTo(201);
        assertThat(create(merchant, 10_000, "AUD", "automatic").body()).containsEntry("code", "unsupported_currency");
        for (TestMerchant unsupported : List.of(createMerchant(BETA), createMerchant())) {
            enable(unsupported);
            assertThat(create(unsupported, 10_000, "USD", "automatic").body())
                    .containsEntry("code", "unsupported_currency");
        }
        assertThat(admin("PATCH", "/admin/v1/merchants/" + merchant.id(), Map.of("name", "Renamed"))
                .body()).containsEntry("international_cards", true);
        assertThat(admin("PATCH", "/admin/v1/merchants/" + merchant.id(), Map.of("international_cards", false))
                .body()).containsEntry("international_cards", false);
        assertThat(create(merchant, 10_000, "USD", "automatic").status()).isEqualTo(422);
        assertThat(count("SELECT count(*) FROM audit_log WHERE resource_id = ? AND details -> 'international_cards' IS NOT NULL",
                merchant.id())).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"INR,99", "INR,100000001", "USD,99", "USD,1000001", "KWD,101", "KWD,3000010"})
    void creationChecksCurrencySpecificRangesAndSteps(String currency, long amount) {
        TestMerchant merchant = internationalMerchant();
        Response response = create(merchant, amount, currency, "automatic");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).containsEntry("code", "validation_error");
        assertThat(count("SELECT count(*) FROM payments WHERE merchant_id = ?", merchant.id())).isZero();
    }

    @ParameterizedTest
    @CsvSource({"JPY,1000", "USD,10000", "KWD,10000"})
    void foreignPaymentsUseCardsWithoutApplyingInrRiskThresholds(String currency, long amount) {
        TestMerchant merchant = internationalMerchant();
        String paymentId = payment(merchant, amount, currency, "automatic");
        assertThat(confirm(merchant, paymentId, upi("intent")).body()).containsEntry("code", "unsupported_payment_method");
        assertThat(confirm(merchant, paymentId, card()).body()).containsEntry("status", "requires_action");
    }

    @Test
    void inrRiskThresholdsStillBlockLargeInrPayments() {
        TestMerchant merchant = internationalMerchant();
        String paymentId = payment(merchant, 1000, "INR", "automatic");
        assertThat(confirm(merchant, paymentId, card()).body()).containsEntry("status", "failed");
    }

    @Test
    void capturesAndRefundsKeepTheCurrencyAndTheProvidersAmountStep() {
        TestMerchant merchant = internationalMerchant();
        String paymentId = payment(merchant, 10_000, "KWD", "manual");
        Response confirmed = confirm(merchant, paymentId, card());
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        assertThat(getPayment(merchant, paymentId)).containsEntry("status", "authorized");
        assertThat(post(merchant, "/v1/payments/" + paymentId + "/capture", key(), Map.of("amount", 4999)).status())
                .isEqualTo(400);
        assertThat(getPayment(merchant, paymentId)).containsEntry("status", "authorized");
        Response captured = post(merchant, "/v1/payments/" + paymentId + "/capture", key(), Map.of("amount", 5000));
        assertThat(captured.body()).containsEntry("status", "succeeded").containsEntry("currency", "KWD");
        assertThat(num(captured.body(), "amount_captured")).isEqualTo(5000);
        assertThat(post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), Map.of("amount", 4999)).status())
                .isEqualTo(400);
        assertThat(post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), Map.of("amount", 5010)).status())
                .isEqualTo(422);
        Response refunded = post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), Map.of());
        assertThat(refunded.body()).containsEntry("status", "succeeded").containsEntry("currency", "KWD");
        assertThat(num(refunded.body(), "amount")).isEqualTo(5000);
    }

    @Test
    void conversionsAreImmutableIdempotentAndAllocateTheExactCapturedValue() {
        TestMerchant merchant = internationalMerchant();
        String paymentId = captureWithoutConversion(merchant, 10_000);
        assertThat(book(paymentId, Kind.CAPTURE, null, 10_000, 834_567, "83.456700")).isEqualTo(Result.RECORDED);
        assertThat(book(paymentId, Kind.CAPTURE, null, 10_000, 834_567, "83.456700")).isEqualTo(Result.UNCHANGED);
        assertThat(book(paymentId, Kind.CAPTURE, null, 10_000, 834_568, null)).isEqualTo(Result.AMOUNT_MISMATCH);
        assertThat(book(paymentId, Kind.REFUND, "first", 4000, 340_000, "85.00")).isEqualTo(Result.RECORDED);
        assertThat(book(paymentId, Kind.REFUND, "rest", 6000, 499_000, null)).isEqualTo(Result.RECORDED);
        assertThat(count("SELECT carried_amount FROM fx_conversions WHERE reference_id = 'first'")).isEqualTo(333_827);
        assertThat(count("SELECT carried_amount FROM fx_conversions WHERE reference_id = 'rest'")).isEqualTo(500_740);
        String attemptId = payments.findById(paymentId).orElseThrow().succeededAttemptId();
        assertThat(conversions.find(Kind.CAPTURE, attemptId).orElseThrow().rate().toPlainString()).isEqualTo("83.456700");
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "INR")).isZero();
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_GAIN_LOSS, "INR")).isEqualTo(-4433);
        assertThat(count("SELECT count(*) FROM fx_conversions")).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.sql("UPDATE fx_conversions SET settled_amount = settled_amount + 1").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM fx_conversions").update()).hasMessageContaining("append-only");
    }

    @Test
    void conversionDependenciesAndReversalsKeepCumulativeAllocationsBounded() {
        TestMerchant merchant = internationalMerchant();
        String paymentId = captureWithoutConversion(merchant, 10_000);
        assertThat(book(paymentId, Kind.REFUND, "refund", 1000, 8500, null)).isEqualTo(Result.MISSING_DEPENDENCY);
        assertThat(book(paymentId, Kind.CAPTURE, null, 10_000, 834_567, null)).isEqualTo(Result.RECORDED);
        assertThat(book(paymentId, Kind.CHARGEBACK_REVERSAL, "dispute", 3000, 260_000, null))
                .isEqualTo(Result.MISSING_DEPENDENCY);
        assertThat(book(paymentId, Kind.CHARGEBACK, "dispute", 3000, 250_000, null)).isEqualTo(Result.RECORDED);
        assertThat(book(paymentId, Kind.CHARGEBACK_REVERSAL, "dispute", 3000, 260_000, null)).isEqualTo(Result.RECORDED);
        assertThat(book(paymentId, Kind.REFUND, "refund", 10_000, 850_000, null)).isEqualTo(Result.RECORDED);
        assertThat(book(paymentId, Kind.REFUND, "extra", 1, 85, null)).isEqualTo(Result.MISSING_DEPENDENCY);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "INR")).isZero();
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_GAIN_LOSS, "INR")).isEqualTo(-5433);
    }

    @Test
    void allocationsRoundHalfEvenAndAllowZeroCarriedPaise() {
        TestMerchant merchant = internationalMerchant();
        String paymentId = captureWithoutConversion(merchant, 200);
        assertThat(book(paymentId, Kind.CAPTURE, null, 200, 100, null)).isEqualTo(Result.RECORDED);
        assertThat(book(paymentId, Kind.REFUND, "half", 1, 1, null)).isEqualTo(Result.RECORDED);
        assertThat(count("SELECT carried_amount FROM fx_conversions WHERE reference_id = 'half'")).isZero();
        assertThat(book(paymentId, Kind.REFUND, "one-and-half", 2, 1, null)).isEqualTo(Result.RECORDED);
        assertThat(count("SELECT carried_amount FROM fx_conversions WHERE reference_id = 'one-and-half'")).isEqualTo(2);
        assertThat(book(paymentId, Kind.REFUND, "remaining", 197, 98, null)).isEqualTo(Result.RECORDED);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "INR")).isZero();
    }

        @Test
        void pspResultsRecordConversionsOnlyForMatchingSuccessfulMovements() {
        TestMerchant merchant = internationalMerchant();
        String paymentId = payment(merchant, 10_000, "USD", "automatic");
        Response confirmed = confirm(merchant, paymentId, card());
        String attemptId = str(confirmed.body(), "latest_attempt.id");
        String reference = str(confirmed.body(), "latest_attempt.provider_reference");
        Conversion capture = new Conversion(Money.of(832_500, "INR"), new BigDecimal("83.25"));
        outcomes.applyProviderEvent(ALPHA, merchant.id(), ProviderEvent.payment(key(), "payment.captured", reference, attemptId,
            ProviderPaymentResult.succeeded(reference, Money.of(10_001, "USD"), "captured").withConversion(capture)));
        assertThat(conversions.find(Kind.CAPTURE, attemptId)).isEmpty();
        simulate(ALPHA, reference, "success", false);
        outcomes.applyProviderEvent(ALPHA, merchant.id(), ProviderEvent.payment(key(), "payment.captured", reference, attemptId,
            ProviderPaymentResult.succeeded(reference, Money.of(10_000, "USD"), "captured").withConversion(capture)));
        assertThat(conversions.find(Kind.CAPTURE, attemptId)).contains(capture);
        Response capturedView = assertContract("GET", "/v1/payments/{payment_id}", null,
            get(merchant, "/v1/payments/" + paymentId));
        assertThat(num(capturedView.body(), "conversion.settled_amount")).isEqualTo(832_500);
        assertThat(str(capturedView.body(), "conversion.rate")).isEqualTo("83.25");

        Response refunded = post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), Map.of("amount", 4006));
        String refundId = str(refunded.body(), "id");
        String refundReference = str(refunded.body(), "provider_reference");
        Conversion refundConversion = new Conversion(Money.of(340_510, "INR"), new BigDecimal("85.00"));
        refunds.applyResult(refundId, ProviderRefundResult.succeeded(refundReference, Money.of(4007, "USD"))
            .withConversion(refundConversion), TransitionSource.STATUS_CHECK);
        assertThat(conversions.find(Kind.REFUND, refundId)).isEmpty();
        refunds.applyResult(refundId, ProviderRefundResult.succeeded(refundReference, Money.of(4006, "USD"))
            .withConversion(refundConversion), TransitionSource.STATUS_CHECK);
        assertThat(conversions.find(Kind.REFUND, refundId)).contains(refundConversion);
        Response refundView = assertContract("GET", "/v1/refunds/{refund_id}", null,
            get(merchant, "/v1/refunds/" + refundId));
        assertThat(num(refundView.body(), "conversion.settled_amount")).isEqualTo(340_510);
        assertThat(str(refundView.body(), "conversion.rate")).isEqualTo("85.00");
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.PSP_RECEIVABLE, "INR")).isEqualTo(491_990);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_GAIN_LOSS, "INR")).isEqualTo(-7010);
        }

    @ParameterizedTest
    @CsvSource({"JPY,1300,71916", "USD,12000,999000", "KWD,40000,1081800"})
    void zeroTwoAndThreeDecimalCurrenciesCaptureAndRefundExactly(String currency, long amount, long settled) {
        TestMerchant merchant = internationalMerchant();
        String paymentId = payment(merchant, amount, currency, "manual");
        Response confirmed = confirm(merchant, paymentId, card());
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        assertThat(getPayment(merchant, paymentId)).containsEntry("status", "authorized").doesNotContainKey("conversion");
        Response captured = assertContract("POST", "/v1/payments/{payment_id}/capture", Map.of(),
                post(merchant, "/v1/payments/" + paymentId + "/capture", key(), Map.of()));
        assertThat(num(captured.body(), "conversion.settled_amount")).isEqualTo(settled);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, currency)).isEqualTo(-amount);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "INR")).isEqualTo(settled);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.PSP_RECEIVABLE, currency)).isZero();
        Response refunded = assertContract("POST", "/v1/payments/{payment_id}/refunds", Map.of(),
                post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), Map.of()));
        assertThat(num(refunded.body(), "amount")).isEqualTo(amount);
        assertThat(num(refunded.body(), "conversion.settled_amount")).isEqualTo(settled);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, currency)).isZero();
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "INR")).isZero();
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.PSP_RECEIVABLE, "INR")).isZero();
    }

    @Test
    void changingRatesAffectsNewRefundsButNotEarlierCapturesOrTheirRetries() {
        TestMerchant merchant = internationalMerchant();
        rate("USD", "83.456700");
        String paymentId = payment(merchant, 10_000, "USD", "automatic");
        Response confirmed = confirm(merchant, paymentId, card());
        String reference = str(confirmed.body(), "latest_attempt.provider_reference");
        simulate(ALPHA, reference, "success", false);
        assertThat(num(getPayment(merchant, paymentId), "conversion.settled_amount")).isEqualTo(834_567);
        rate("USD", "85.00");
        simulate(ALPHA, reference, "success", true);
        assertThat(num(getPayment(merchant, paymentId), "conversion.settled_amount")).isEqualTo(834_567);
        String firstKey = key();
        Response first = post(merchant, "/v1/payments/" + paymentId + "/refunds", firstKey, Map.of("amount", 4000));
        assertThat(num(first.body(), "conversion.settled_amount")).isEqualTo(340_000);
        rate("USD", "83.1666666667");
        assertThat(post(merchant, "/v1/payments/" + paymentId + "/refunds", firstKey, Map.of("amount", 4000)).raw())
                .isEqualTo(first.raw());
        Response rest = post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), Map.of("amount", 6000));
        assertThat(num(rest.body(), "conversion.settled_amount")).isEqualTo(499_000);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "USD")).isZero();
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "INR")).isZero();
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_GAIN_LOSS, "INR")).isEqualTo(-4433);
    }

    @Test
    void settlementReportsFillMissingConversionsAndReconcileMixedCurrenciesInInr() {
        TestMerchant merchant = createMerchant(ALPHA);
        enable(merchant);
        String paymentId = payment(merchant, 10_006, "USD", "automatic");
        Response confirmed = confirm(merchant, paymentId, card());
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        assertThat(getPayment(merchant, paymentId)).doesNotContainKey("conversion");
        Response refund = post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), Map.of("amount", 106));
        assertThat(refund.body()).containsEntry("status", "succeeded").doesNotContainKey("conversion");
        payAndSucceed(merchant, 200);
        ReconciliationService.RunSummary run = reconcile(merchant);
        assertThat(run.exceptions()).isEmpty();
        assertThat(run.linesMatched()).isEqualTo(3);
        assertThat(run.grossAmount()).isEqualTo(833_200);
        assertThat(run.refundAmount()).isEqualTo(8824);
        assertThat(num(getPayment(merchant, paymentId), "conversion.settled_amount")).isEqualTo(833_000);
        Response refundView = assertContract("GET", "/v1/refunds/{refund_id}", null,
                get(merchant, "/v1/refunds/" + str(refund.body(), "id")));
        assertThat(num(refundView.body(), "conversion.settled_amount")).isEqualTo(8824);
        assertThat(count("SELECT count(*) FROM fx_conversions WHERE source = 'SETTLEMENT_REPORT' AND rate IS NULL"))
                .isEqualTo(2);
        assertThat(count("SELECT charged_amount FROM reconciliation_lines WHERE line_type = 'REFUND'"))
                .isEqualTo(106);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.PSP_RECEIVABLE, "INR")).isZero();
        long postings = count("SELECT count(*) FROM ledger_transactions");
        assertThat(reconcile(merchant).exceptions()).isEmpty();
        assertThat(count("SELECT count(*) FROM ledger_transactions")).isEqualTo(postings);
    }

    @Test
    void reconciliationHealsForeignCapturesUsingTheOriginalAmountAndTheReportedInr() {
        TestMerchant merchant = internationalMerchant();
        String paymentId = payment(merchant, 10_004, "USD", "automatic");
        assertThat(confirm(merchant, paymentId, card()).body()).containsEntry("status", "processing");
        ReconciliationService.RunSummary run = reconcile(merchant);
        assertThat(run.exceptions()).isEmpty();
        assertThat(run.linesAutoHealed()).isEqualTo(1);
        Map<String, Object> payment = getPayment(merchant, paymentId);
        assertThat(payment).containsEntry("status", "succeeded").containsEntry("currency", "USD");
        assertThat(num(payment, "amount_captured")).isEqualTo(10_004);
        assertThat(num(payment, "conversion.settled_amount")).isEqualTo(832_833);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.PSP_RECEIVABLE, "INR")).isZero();
    }

        @Test
        void delayedCaptureConversionCanResolveARefundExceptionOnTheNextRun() {
        TestMerchant merchant = internationalMerchant();
        Instant capturedAt = clock.instant();
        Map<String, Object> payment = payForeign(merchant, 10_006, "USD");
        clock.advance(Duration.ofDays(1));
        Response refund = post(merchant, "/v1/payments/" + str(payment, "id") + "/refunds", key(), Map.of("amount", 106));
        ReconciliationService.RunSummary first = reconcile(merchant);
        assertThat(first.exceptions()).extracting(ReconciliationService.ExceptionView::type).contains("conversion_missing");
        assertThat(conversions.find(Kind.REFUND, str(refund.body(), "id"))).isEmpty();
        assertThat(reconciliation.run(merchant.id(), ALPHA, capturedAt.minusSeconds(1), capturedAt.plusSeconds(1))
            .exceptions()).isEmpty();
        assertThat(reconcile(merchant).exceptions()).isEmpty();
        assertThat(count("SELECT count(*) FROM reconciliation_exceptions WHERE type = 'CONVERSION_MISSING' AND status = 'RESOLVED'"))
            .isEqualTo(1);
        assertThat(conversions.find(Kind.REFUND, str(refund.body(), "id"))).isPresent();
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.PSP_RECEIVABLE, "INR")).isZero();
        }

        @Test
        void reportHealsAPendingForeignRefundAndRejectsAConflictingSettlement() {
        TestMerchant merchant = internationalMerchant();
        Map<String, Object> payment = payForeign(merchant, 10_000, "USD");
        Response refund = post(merchant, "/v1/payments/" + str(payment, "id") + "/refunds", key(), Map.of("amount", 4007));
        assertThat(refund.body()).containsEntry("status", "pending");
        ReconciliationService.RunSummary run = reconcile(merchant);
        assertThat(run.exceptions()).isEmpty();
        assertThat(run.linesAutoHealed()).isEqualTo(1);
        assertThat(get(merchant, "/v1/refunds/" + str(refund.body(), "id")).body()).containsEntry("status", "succeeded");
        psp().overrideReportedAmount(str(payment, "latest_attempt.provider_reference"), 832_501);
        assertThat(reconcile(merchant).exceptions()).filteredOn(value -> value.type().equals("amount_mismatch"))
            .singleElement().satisfies(value -> {
                assertThat(value.expectedAmount()).isEqualTo(832_500);
                assertThat(value.actualAmount()).isEqualTo(832_501);
            });
        assertThat(num(getPayment(merchant, str(payment, "id")), "conversion.settled_amount")).isEqualTo(832_500);
        }

        @Test
        void historicalChargebackAndReversalCanBeValuedAfterAFullRefund() {
        TestMerchant merchant = internationalMerchant();
        Map<String, Object> payment = payForeign(merchant, 10_000, "USD");
        String paymentId = str(payment, "id");
        rate("USD", "85.00");
        Response opened = send("POST", "/simulator/" + ALPHA + "/payments/"
            + str(payment, "latest_attempt.provider_reference") + "/dispute", Map.of(), Map.of("amount", 4000));
        assertThat(opened.status()).as(opened.raw()).isEqualTo(200);
        String disputeReference = str(opened.body(), "dispute_id");
        rate("USD", "84.00");
        assertThat(send("POST", "/simulator/" + ALPHA + "/disputes/" + disputeReference + "/status", Map.of(),
            Map.of("status", "won")).status()).isEqualTo(200);
        rate("USD", "83.00");
        assertThat(post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), Map.of()).body())
            .containsEntry("status", "succeeded");
        ReconciliationService.RunSummary run = reconcile(merchant);
        assertThat(run.exceptions()).isEmpty();
        assertThat(run.linesMatched()).isEqualTo(4);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "USD")).isZero();
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "INR")).isZero();
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_GAIN_LOSS, "INR")).isEqualTo(-1500);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.PSP_RECEIVABLE, "INR")).isZero();
        String disputeId = str(list(get(merchant, "/v1/payments/" + paymentId + "/disputes").body(), "data").getFirst(), "id");
        Response dispute = assertContract("GET", "/v1/disputes/{dispute_id}", null,
            get(merchant, "/v1/disputes/" + disputeId));
        assertThat(dispute.body()).containsEntry("status", "won");
        assertThat(num(dispute.body(), "conversion.settled_amount")).isEqualTo(340_000);
        assertThat(conversions.find(Kind.CHARGEBACK_REVERSAL, disputeId).orElseThrow().settled().amount()).isEqualTo(336_000);
        }

        @Test
        void foreignDisputesKnownOnlyFromAReportAreIngestedInTheChargedCurrency() {
        TestMerchant merchant = internationalMerchant();
        Map<String, Object> payment = payForeign(merchant, 10_000, "USD");
        Response opened = send("POST", "/simulator/" + ALPHA + "/payments/"
            + str(payment, "latest_attempt.provider_reference") + "/dispute", Map.of(),
            Map.of("amount", 4000, "send_webhook", false));
        assertThat(opened.status()).isEqualTo(200);
        ReconciliationService.RunSummary run = reconcile(merchant);
        assertThat(run.exceptions()).isEmpty();
        assertThat(run.linesAutoHealed()).isEqualTo(1);
        Map<String, Object> dispute = list(get(merchant, "/v1/payments/" + str(payment, "id") + "/disputes").body(), "data").getFirst();
        assertThat(dispute).containsEntry("currency", "USD").containsEntry("status", "open");
        assertThat(num(dispute, "amount")).isEqualTo(4000);
        assertThat(num(dispute, "conversion.settled_amount")).isEqualTo(333_000);
        assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.PSP_RECEIVABLE, "INR")).isZero();
        }

        @Test
        void simultaneousRefundsSerializeTheirConversionAllocations() throws Exception {
            TestMerchant merchant = internationalMerchant();
            rate("USD", "83.4567");
            String paymentId = str(payForeign(merchant, 10_000, "USD"), "id");
            rate("USD", "85.00");
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Response>> pending = tx.execute(status -> {
                    payments.lockById(paymentId).orElseThrow();
                    List<Future<Response>> tasks = List.of(4000, 6000).stream().map(amount -> executor.submit(() ->
                            post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), Map.of("amount", amount)))).toList();
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count("""
                            SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                              AND wait_event_type = 'Lock' AND query ILIKE '%FROM payments%FOR UPDATE%'
                            """)).isGreaterThanOrEqualTo(2));
                    return tasks;
                });
                for (Future<Response> result : pending) {
                    Response response = result.get(10, TimeUnit.SECONDS);
                    assertThat(response.status()).as(response.raw()).isEqualTo(201);
                    assertThat(response.body()).containsEntry("status", "succeeded");
                }
            }
            assertThat(count("SELECT count(*) FROM fx_conversions")).isEqualTo(3);
            assertThat(count("SELECT sum(carried_amount) FROM fx_conversions WHERE kind = 'REFUND'")).isEqualTo(834_567);
            assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "USD")).isZero();
            assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_CONVERSION, "INR")).isZero();
            assertThat(ledger.balance(merchant.id(), ALPHA, LedgerAccountType.FX_GAIN_LOSS, "INR")).isEqualTo(-15_433);
        }

        @Test
        void databaseRejectsInvalidConversionAmountsCurrenciesRatesAndCaptureReferences() {
            TestMerchant merchant = internationalMerchant();
            payForeign(merchant, 10_000, "USD");
            List<String> columns = List.of("merchant_id", "provider_code", "payment_id", "attempt_id", "kind", "reference_id",
                    "amount", "currency", "settled_amount", "carried_amount", "rate", "source", "recorded_at");
            List<Map<String, String>> invalid = List.of(Map.of("amount", "0"), Map.of("currency", "'INR'"),
                Map.of("settled_amount", "0", "kind", "'REFUND'"), Map.of("carried_amount", "-1", "kind", "'REFUND'"),
                Map.of("carried_amount", "settled_amount + 1"),
                    Map.of("rate", "0"), Map.of("rate", "'NaN'::numeric"), Map.of("rate", "'Infinity'::numeric"),
                    Map.of("kind", "'OTHER'"), Map.of("source", "'OTHER'"), Map.of("reference_id", "'wrong_attempt'"));
            for (Map<String, String> fields : invalid) {
                String paymentId = payment(merchant, 10_000, "USD", "automatic");
                String attemptId = str(confirm(merchant, paymentId, card()).body(), "latest_attempt.id");
                Map<String, String> scope = Map.of("payment_id", ":paymentId", "attempt_id", ":attemptId", "reference_id", ":attemptId");
                String selected = columns.stream().map(column -> fields.getOrDefault(column, scope.getOrDefault(column, column)))
                    .collect(Collectors.joining(", "));
                assertThatThrownBy(() -> jdbc.sql("INSERT INTO fx_conversions (id, " + String.join(", ", columns)
                    + ") SELECT 'invalid', " + selected + " FROM fx_conversions WHERE kind = 'CAPTURE'")
                    .param("paymentId", paymentId).param("attemptId", attemptId).update())
                        .as(fields.toString()).hasMessageContaining("check constraint");
            }
            assertThat(count("SELECT count(*) FROM fx_conversions")).isEqualTo(1);
        }

        @Test
        void aConversionCannotBeRecordedForAnUncapturedAttempt() {
            TestMerchant merchant = internationalMerchant();
            String paymentId = payment(merchant, 10_000, "USD", "manual");
            Response confirmed = confirm(merchant, paymentId, card());
            String attemptId = str(confirmed.body(), "latest_attempt.id");
            simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
            Result result = tx.execute(status -> conversions.record(payments.lockById(paymentId).orElseThrow(), attemptId,
                    Kind.CAPTURE, attemptId, Money.of(10_000, "USD"), new Conversion(Money.of(832_500, "INR"), null), Source.PSP));
            assertThat(result).isEqualTo(Result.MISSING_DEPENDENCY);
            assertThat(count("SELECT count(*) FROM fx_conversions")).isZero();
            assertThat(count("SELECT count(*) FROM ledger_transactions")).isZero();
        }

            @ParameterizedTest
            @CsvSource({"USD,9999", "JPY,10000"})
            void matchingInrTotalsCannotHideAConflictingOriginalCharge(String currency, long charged) {
            TestMerchant merchant = internationalMerchant();
            payForeign(merchant, 10_000, "USD");
            SettlementReport actual = providerClient.fetchSettlementReport(ALPHA,
                new SettlementReportQuery(merchant.id(), clock.instant().minusSeconds(3600), clock.instant().plusSeconds(1)));
            SettlementReport.Line line = actual.lines().getFirst();
            SettlementReport.Line conflicting = new SettlementReport.Line(line.lineId(), line.type(), line.providerReference(),
                line.merchantReference(), line.amount(), line.fee(), line.settlementId(), line.occurredAt(),
                line.description(), Money.of(charged, currency));
            doReturn(new SettlementReport(List.of(conflicting), actual.settlements())).when(alpha)
                .fetchSettlementReport(any(MerchantAccount.class), any(SettlementReportQuery.class));
            ReconciliationService.RunSummary run = reconcile(merchant);
            assertThat(run.linesMatched()).isZero();
            assertThat(run.exceptions()).filteredOn(value -> value.type().equals("amount_mismatch")).singleElement()
                .satisfies(value -> {
                    assertThat(value.expectedAmount()).isEqualTo(10_000);
                    assertThat(value.actualAmount()).isEqualTo(charged);
                    assertThat(value.details()).contains("Original charge differs");
                });
            }

            private Map<String, Object> payForeign(TestMerchant merchant, long amount, String currency) {
        String paymentId = payment(merchant, amount, currency, "automatic");
        Response confirmed = confirm(merchant, paymentId, card());
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        Map<String, Object> result = getPayment(merchant, paymentId);
        assertThat(result).containsEntry("status", "succeeded");
        return result;
        }

        private MockPsp psp() {
        return mockProviders.stream().filter(provider -> provider.code().equals(ALPHA)).findFirst().orElseThrow().psp();
        }

        private ReconciliationService.RunSummary reconcile(TestMerchant merchant) {
        return reconciliation.run(merchant.id(), ALPHA, clock.instant().minusSeconds(3600), clock.instant().plusSeconds(1));
    }

    private void rate(String currency, String rate) {
        Response response = send("POST", "/simulator/" + ALPHA + "/fx-rates", Map.of(), Map.of("currency", currency, "rate", rate));
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        assertThat(response.body()).containsEntry(currency, rate);
    }

        private String captureWithoutConversion(TestMerchant merchant, long amount) {
        String paymentId = payment(merchant, amount, "USD", "automatic");
        Response confirmed = confirm(merchant, paymentId, card());
        outcomes.apply(paymentId, str(confirmed.body(), "latest_attempt.id"), new AttemptUpdate(AttemptStatus.SUCCEEDED,
                str(confirmed.body(), "latest_attempt.provider_reference"), null, null, Money.of(amount, "USD")),
                TransitionSource.PROVIDER_RESPONSE);
        return paymentId;
    }

    private Result book(String paymentId, Kind kind, String referenceId, long amount, long settled, String rate) {
        return tx.execute(status -> {
            Payment payment = payments.lockById(paymentId).orElseThrow();
            String attemptId = payment.succeededAttemptId();
            return conversions.record(payment, attemptId, kind, referenceId == null ? attemptId : referenceId,
                    Money.of(amount, payment.amount().currency()),
                    new Conversion(Money.of(settled, "INR"), rate == null ? null : new BigDecimal(rate)), Source.PSP);
        });
    }

    private TestMerchant internationalMerchant() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);
        enable(merchant);
        return merchant;
    }

    private void enable(TestMerchant merchant) {
        Response response = admin("PATCH", "/admin/v1/merchants/" + merchant.id(), Map.of("international_cards", true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).containsEntry("international_cards", true);
    }

    private Response create(TestMerchant merchant, long amount, String currency, String captureMethod) {
        Map<String, Object> body = Map.of("amount", amount, "currency", currency,
            "merchant_order_id", key(), "capture_method", captureMethod);
        return assertContract("POST", "/v1/payments", body, post(merchant, "/v1/payments", key(), body));
    }

    private String payment(TestMerchant merchant, long amount, String currency, String captureMethod) {
        Response response = create(merchant, amount, currency, captureMethod);
        assertThat(response.status()).as(response.raw()).isEqualTo(201);
        return str(response.body(), "id");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}