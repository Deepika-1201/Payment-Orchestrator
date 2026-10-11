package com.payments.gateway.billing;

import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.payments.gateway.reconciliation.ReconciliationService;
import com.payments.gateway.payment.application.PaymentOutcomeService;
import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.events.BillableActivity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ApplicationEventPublisher;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

class BillingIntegrationTest extends IntegrationTest {

    @MockitoSpyBean
    private BillingRepository repository;
    @Autowired
    private ReconciliationService reconciliation;
    @Autowired
    private BillingService billing;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private PaymentOutcomeService outcomes;
    @Autowired
    private ApplicationEventPublisher events;

    @Test
    void billingPlansAreProspectiveImmutableAndMerchantScoped() {
        TestMerchant merchant = createMerchant(ALPHA);
        TestMerchant other = createMerchant(ALPHA);
        Instant now = clock.instant();
        Response backdated = plan(other, now.minusSeconds(1), 10, 100, 0);
        assertThat(backdated.status()).isEqualTo(400);
        assertThat(backdated.raw()).contains("effective_at", "cannot be backdated");
        assertThat(count("SELECT count(*) FROM billing_accounts WHERE merchant_id = ?", other.id())).isZero();
        Response first = plan(merchant, now, 17, 125, 0);
        assertThat(first.status()).as(first.raw()).isEqualTo(201);
        assertThat(repository.effective(merchant.id(), now.minusNanos(1000))).isEmpty();
        assertThat(repository.effective(merchant.id(), now).orElseThrow().id()).isEqualTo(str(first.body(), "id"));
        Response future = plan(merchant, now.plus(Duration.ofDays(1)), 50, 200, 5000);
        assertThat(future.status()).isEqualTo(201);
        assertThat(repository.effective(merchant.id(), now).orElseThrow().id()).isEqualTo(str(first.body(), "id"));
        assertThat(repository.effective(merchant.id(), now.plus(Duration.ofDays(1))).orElseThrow().id())
                .isEqualTo(str(future.body(), "id"));
        assertThat(repository.effective(other.id(), now.plus(Duration.ofDays(1)))).isEmpty();
        assertThat(list(admin("GET", path(merchant) + "/billing-plans", null).body(), "data")).hasSize(2);
        assertThat(plan(merchant, now, 10, 100, 0).status()).isEqualTo(409);
        assertThat(plan(merchant, now.minusSeconds(1), 10, 100, 0).status()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM billing_plans")).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.sql("UPDATE billing_plans SET monthly_minimum = 0").update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM billing_plan_prices").update()).hasMessageContaining("append-only");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'billing_plan.published'")).isEqualTo(2);
    }

    @Test
    void planPublicationRequiresFinanceAndValidExplicitPrices() {
        TestMerchant merchant = createMerchant();
        Map<String, Object> request = Map.of("name", "Standard", "payment_fees", Map.of("card", fee(20, 100)));
        assertThat(send("POST", path(merchant) + "/billing-plans", Map.of("Authorization", "Bearer test-ops-token"), request).status())
                .isEqualTo(403);
        Response published = send("POST", path(merchant) + "/billing-plans", Map.of("Authorization", "Bearer test-finance-token"), request);
        assertThat(published.status()).isEqualTo(201);
        assertThat(num(published.body(), "monthly_minimum")).isZero();
        assertThat(admin("POST", path(merchant) + "/billing-plans", Map.of("name", "Bad", "payment_fees", Map.of("card", fee(-1, 0))))
                .status()).isEqualTo(400);
        assertThat(admin("POST", path(merchant) + "/billing-plans", Map.of("name", "Bad", "payment_fees", Map.of("unknown", fee(0, 0))))
                .status()).isEqualTo(400);
    }

    @Test
    void acceptedPaymentsAreBilledOnceAndUnenrolledPaymentsAreIgnored() {
        TestMerchant merchant = createMerchant(ALPHA);
        payCard(merchant, 10_000, "INR");
        assertThat(count("SELECT count(*) FROM billing_events")).isZero();
        plan(merchant, clock.instant(), 17, 125, 0);
        Map<String, Object> payment = payCard(merchant, 12_340, "INR");
        simulate(ALPHA, str(payment, "latest_attempt.provider_reference"), "success", true);
        assertThat(count("SELECT count(*) FROM billing_events")).isEqualTo(1);
        assertThat(count("SELECT service_fee FROM billing_events")).isEqualTo(171);
        post(merchant, "/v1/payments/" + str(payment, "id") + "/refunds", UUID.randomUUID().toString(), Map.of("amount", 1000));
        assertThat(count("SELECT count(*) FROM billing_events")).isEqualTo(1);
    }

    @Test
    void merchantRefundFeesAreSeparateAndLateAutomaticRefundsAreNotBilled() {
        TestMerchant merchant = createMerchant(ALPHA);
        admin("POST", path(merchant) + "/billing-plans", Map.of("name", "With refunds",
                "payment_fees", Map.of("card", fee(10, 100)), "refund_fees", Map.of("card", fee(7, 50))));
        Map<String, Object> paid = payCard(merchant, 10_000, "INR");
        String refundKey = UUID.randomUUID().toString();
        String refundPath = "/v1/payments/" + str(paid, "id") + "/refunds";
        post(merchant, refundPath, refundKey, Map.of("amount", 2000));
        post(merchant, refundPath, refundKey, Map.of("amount", 2000));
        assertThat(count("SELECT count(*) FROM billing_events")).isEqualTo(2);
        assertThat(count("SELECT service_fee FROM billing_events WHERE kind = 'REFUND'")).isEqualTo(17);
        String lateId = str(createPayment(merchant, 10_000, "automatic"), "id");
        Response confirmed = confirm(merchant, lateId, card());
        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireDue();
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        assertThat(count("SELECT count(*) FROM refunds WHERE initiated_by = 'SYSTEM_LATE_SUCCESS'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM billing_events")).isEqualTo(2);
    }

    @Test
    void delayedConversionCompletesTheOriginalBillingPlanWithoutRepricingIt() {
        TestMerchant merchant = createMerchant(ALPHA);
        admin("PATCH", path(merchant), Map.of("international_cards", true));
        Response original = plan(merchant, clock.instant(), 10, 100, 0);
        payCard(merchant, 10_006, "USD");
        assertThat(count("SELECT count(*) FROM billing_events WHERE settled_amount IS NULL AND service_fee IS NULL")).isEqualTo(1);
        clock.advance(Duration.ofHours(1));
        plan(merchant, clock.instant(), 500, 1000, 0);
        reconciliation.run(merchant.id(), ALPHA, clock.instant().minus(Duration.ofDays(1)), clock.instant().plusSeconds(1));
        BillingEvent event = repository.events(merchant.id(), 10).getFirst();
        assertThat(event.planId()).isEqualTo(str(original.body(), "id"));
        assertThat(event.settledAmount()).isEqualTo(833_000);
        assertThat(event.serviceFee()).isEqualTo(8340);
        assertThatThrownBy(() -> jdbc.sql("UPDATE billing_events SET service_fee = 1").update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE billing_events SET recognized_at = recognized_at + interval '1 second'").update())
                .hasMessageContaining("immutable");
    }

    @Test
    void monthlyInvoicesFreezeExactTotalsMinimumAndTaxAndReplayIdentically() {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        TestMerchant merchant = createMerchant(ALPHA);
        Response firstPlan = plan(merchant, clock.instant(), 17, 125, 1000);
        payCard(merchant, 12_340, "INR");
        clock.advance(Duration.ofDays(1));
        plan(merchant, clock.instant(), 50, 200, 500);
        payCard(merchant, 5000, "INR");
        assertThat(issue(merchant, "2026-10").status()).isEqualTo(409);
        clock.set(Instant.parse("2026-10-31T18:30:00Z"));
        Response invoice = issue(merchant, "2026-10");
        assertThat(invoice.status()).as(invoice.raw()).isEqualTo(201);
        assertThat(num(invoice.body(), "subtotal")).isEqualTo(500);
        assertThat(num(invoice.body(), "tax")).isEqualTo(90);
        assertThat(num(invoice.body(), "total")).isEqualTo(590);
        assertThat(str(invoice.body(), "number")).isEqualTo("INV/2026/000001");
        assertThat(str(invoice.body(), "collection_mode")).isEqualTo("MERCHANT_PAYMENT");
        assertThat(list(invoice.body(), "lines")).hasSize(3).anySatisfy(line -> {
            assertThat(str(line, "plan_id")).isEqualTo(str(firstPlan.body(), "id"));
            assertThat(num(line, "amount")).isEqualTo(171);
        });
        assertThat(issue(merchant, "2026-10").raw()).isEqualTo(invoice.raw());
        assertThat(count("SELECT count(*) FROM billing_event_assignments")).isEqualTo(2);
        assertThat(list(admin("GET", path(merchant) + "/billing-events?unbilled=true", null).body(), "data")).isEmpty();
        assertThat(list(admin("GET", path(merchant) + "/billing-events", null).body(), "data")).allSatisfy(event -> {
            assertThat(str(event, "invoice_id")).isEqualTo(str(invoice.body(), "id"));
            assertThat(str(event, "pricing_status")).isEqualTo("priced");
        });
        assertThatThrownBy(() -> jdbc.sql("UPDATE billing_invoices SET total = total + 1").update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM billing_invoice_lines").update()).hasMessageContaining("append-only");
        TestMerchant other = createMerchant();
        assertThat(admin("GET", path(other) + "/invoices/" + str(invoice.body(), "id"), null).status()).isEqualTo(404);
    }

    @Test
    void missingPricesBlockIssuanceUntilExplicitlyResolvedAndMonthsCannotBeSkipped() {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        TestMerchant merchant = createMerchant(ALPHA);
        admin("POST", path(merchant) + "/billing-plans", Map.of("name", "Missing method", "payment_fees", Map.of(), "monthly_minimum", 0));
        payCard(merchant, 10_000, "INR");
        BillingEvent event = repository.events(merchant.id(), 10).getFirst();
        clock.set(Instant.parse("2026-11-30T18:30:00Z"));
        assertThat(issue(merchant, "2026-11").body()).containsEntry("code", "billing_invalid_state");
        Response blocked = issue(merchant, "2026-10");
        assertThat(blocked.body()).containsEntry("code", "billing_invoice_blocked");
        assertThat(blocked.raw()).contains(event.id(), "price_pending");
        assertThat(count("SELECT count(*) FROM billing_series")).isZero();
        assertThat(admin("POST", path(merchant) + "/billing-events/" + event.id() + "/price",
                Map.of("amount", 200, "reason", "Agreed fixed service fee")).status()).isEqualTo(200);
        assertThat(admin("POST", path(merchant) + "/billing-events/" + event.id() + "/price",
                Map.of("amount", 201, "reason", "Cannot replace")).status()).isEqualTo(409);
        Response invoice = issue(merchant, "2026-10");
        assertThat(invoice.status()).as(invoice.raw()).isEqualTo(201);
        assertThat(num(invoice.body(), "total")).isEqualTo(236);
        Response empty = issue(merchant, "2026-11");
        assertThat(empty.status()).as(empty.raw()).isEqualTo(201);
        assertThat(num(empty.body(), "total")).isZero();
        assertThat(str(empty.body(), "number")).isEqualTo("INV/2026/000002");
    }

    @Test
    void partialCreditNotesAreBoundedIdempotentAndReverseTheOriginalTaxExactly() {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        TestMerchant merchant = createMerchant(ALPHA);
        plan(merchant, clock.instant(), 101, 0, 0);
        payCard(merchant, 10_000, "INR");
        clock.set(Instant.parse("2026-10-31T18:30:00Z"));
        Response invoice = issue(merchant, "2026-10");
        String invoiceId = str(invoice.body(), "id");
        String lineId = str(list(invoice.body(), "lines").getFirst(), "id");
        Response first = credit(merchant, invoiceId, "correction-1", lineId, 40);
        assertThat(first.status()).as(first.raw()).isEqualTo(201);
        assertThat(num(first.body(), "tax")).isEqualTo(7);
        assertThat(str(first.body(), "number")).isEqualTo("CN/2026/000001");
        assertThat(credit(merchant, invoiceId, "correction-1", lineId, 40).raw()).isEqualTo(first.raw());
        assertThat(credit(merchant, invoiceId, "correction-1", lineId, 41).body()).containsEntry("code", "billing_reference_reuse");
        assertThat(credit(merchant, "binv_other", "correction-1", lineId, 40).body()).containsEntry("code", "billing_reference_reuse");
        assertThat(admin("POST", path(merchant) + "/invoices/" + invoiceId + "/credit-notes", Map.of("reference", "correction-1",
            "reason", "Changed reason", "lines", List.of(Map.of("invoice_line_id", lineId, "amount", 40)))).body())
            .containsEntry("code", "billing_reference_reuse");
        assertThat(admin("POST", path(merchant) + "/invoices/" + invoiceId + "/credit-notes", Map.of("reference", "duplicate-lines",
            "reason", "Invalid duplicate", "lines", List.of(Map.of("invoice_line_id", lineId, "amount", 1),
                Map.of("invoice_line_id", lineId, "amount", 1)))).status()).isEqualTo(400);
        assertThat(credit(merchant, invoiceId, "wrong-line", "bil_other", 1).status()).isEqualTo(400);
        assertThat(credit(merchant, invoiceId, "too-much", lineId, 62).status()).isEqualTo(409);
        Response remainder = credit(merchant, invoiceId, "correction-2", lineId, 61);
        assertThat(remainder.status()).as(remainder.raw()).isEqualTo(201);
        assertThat(num(remainder.body(), "tax")).isEqualTo(11);
        assertThat(str(remainder.body(), "number")).isEqualTo("CN/2026/000002");
        assertThat(count("SELECT sum(tax) FROM billing_credit_notes")).isEqualTo(18);
        assertThat(count("SELECT sum(subtotal) FROM billing_credit_notes")).isEqualTo(101);
        assertThat(admin("GET", path(merchant) + "/invoices/" + invoiceId, null).raw()).isEqualTo(invoice.raw());
        assertThat(count("SELECT count(*) FROM refunds")).isZero();
        assertThatThrownBy(() -> jdbc.sql("UPDATE billing_credit_notes SET reason = 'edited'").update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM billing_credit_lines").update()).hasMessageContaining("append-only");
        assertThat(list(admin("GET", path(merchant) + "/invoices/" + invoiceId + "/credit-notes", null).body(), "data")).hasSize(2);
    }

    @Test
    void billingWorkerPagesPastBlockedAccountsAndNeverSkipsTheirBlockedMonth() {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        var merchants = java.util.List.of(createMerchant(ALPHA), createMerchant(ALPHA), createMerchant(ALPHA)).stream()
                .sorted(java.util.Comparator.comparing(TestMerchant::id)).toList();
        for (TestMerchant merchant : merchants.subList(0, 2)) {
            admin("POST", path(merchant) + "/billing-plans", Map.of("name", "Missing method", "payment_fees", Map.of()));
            payCard(merchant, 10_000, "INR");
        }
        plan(merchants.getLast(), clock.instant(), 100, 0, 200);
        clock.set(Instant.parse("2026-11-30T18:30:00Z"));
        BillingJob job = new BillingJob(repository, billing, new BillingProperties(2, 2), clock);
        assertThat(job.run()).isEqualTo(new BillingJob.RunResult(2, 0, 2));
        assertThat(job.run()).isEqualTo(new BillingJob.RunResult(1, 2, 0));
        assertThat(count("SELECT count(*) FROM billing_invoices")).isEqualTo(2);
        assertThat(repository.invoices(merchants.getFirst().id())).isEmpty();
        assertThat(job.run()).isEqualTo(new BillingJob.RunResult(2, 0, 2));
        assertThat(count("SELECT count(*) FROM billing_invoices")).isEqualTo(2);
    }

    @Test
    void concurrentIssuanceReusesOneInvoiceAndNumber() throws Exception {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        TestMerchant merchant = createMerchant(ALPHA);
        plan(merchant, clock.instant(), 101, 0, 0);
        payCard(merchant, 10_000, "INR");
        clock.set(Instant.parse("2026-10-31T18:30:00Z"));
        List<Response> invoices = concurrently(merchant, () -> issue(merchant, "2026-10"));
        assertThat(invoices).allSatisfy(invoice -> assertThat(invoice.status()).as(invoice.raw()).isEqualTo(201));
        assertThat(invoices.getFirst().raw()).isEqualTo(invoices.getLast().raw());
        assertThat(count("SELECT count(*) FROM billing_invoices")).isEqualTo(1);
        assertThat(count("SELECT last_value FROM billing_series WHERE series = 'INV'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM billing_event_assignments")).isEqualTo(1);
    }

    @Test
    void concurrentCreditsCannotExceedTheOriginalLine() throws Exception {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        TestMerchant merchant = createMerchant(ALPHA);
        plan(merchant, clock.instant(), 101, 0, 0);
        payCard(merchant, 10_000, "INR");
        clock.set(Instant.parse("2026-10-31T18:30:00Z"));
        Response invoice = issue(merchant, "2026-10");
        String invoiceId = str(invoice.body(), "id");
        String lineId = str(list(invoice.body(), "lines").getFirst(), "id");
        List<Response> notes = concurrently(merchant, () -> credit(merchant, invoiceId, UUID.randomUUID().toString(), lineId, 60));
        assertThat(notes).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
        assertThat(count("SELECT sum(amount) FROM billing_credit_lines")).isEqualTo(60);
        assertThat(count("SELECT last_value FROM billing_series WHERE series = 'CN'")).isEqualTo(1);
    }

    @Test
    void failedIssuanceRollsBackNumberHeaderLinesAndAssignments() {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        TestMerchant merchant = createMerchant(ALPHA);
        plan(merchant, clock.instant(), 101, 0, 0);
        payCard(merchant, 10_000, "INR");
        clock.set(Instant.parse("2026-10-31T18:30:00Z"));
        BillingRepository target = AopTestUtils.getUltimateTargetObject(repository);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (failOnce.getAndSet(false)) {
                throw new IllegalStateException("Simulated failure after invoice writes");
            }
            return null;
        }).when(target).insertInvoice(any(BillingDocuments.Invoice.class), anyString(), anyInt(), anyLong());
        assertThat(issue(merchant, "2026-10").status()).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM billing_invoices")).isZero();
        assertThat(count("SELECT count(*) FROM billing_invoice_lines")).isZero();
        assertThat(count("SELECT count(*) FROM billing_event_assignments")).isZero();
        assertThat(count("SELECT count(*) FROM billing_series")).isZero();
        Response retry = issue(merchant, "2026-10");
        assertThat(retry.status()).as(retry.raw()).isEqualTo(201);
        assertThat(str(retry.body(), "number")).isEqualTo("INV/2026/000001");
    }

    @Test
    void unknownFxBlocksClosedMonthAndLaterConversionKeepsTheOriginalPrice() {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        TestMerchant merchant = createMerchant(ALPHA);
        admin("PATCH", path(merchant), Map.of("international_cards", true));
        plan(merchant, clock.instant(), 10, 100, 0);
        payCard(merchant, 10_006, "USD");
        BillingEvent event = repository.events(merchant.id(), 10).getFirst();
        clock.set(Instant.parse("2026-10-31T18:30:00Z"));
        plan(merchant, clock.instant(), 500, 1000, 0);
        assertThat(issue(merchant, "2026-10").raw()).contains("billing_invoice_blocked", "conversion_pending", event.id());
        assertThat(admin("POST", path(merchant) + "/billing-events/" + event.id() + "/price", Map.of("amount", 1, "reason", "No FX yet"))
                .status()).isEqualTo(409);
        reconciliation.run(merchant.id(), ALPHA, Instant.parse("2026-10-09T18:30:00Z"), Instant.parse("2026-10-10T18:30:00Z"));
        Response invoice = issue(merchant, "2026-10");
        assertThat(invoice.status()).as(invoice.raw()).isEqualTo(201);
        assertThat(num(invoice.body(), "subtotal")).isEqualTo(8340);
        assertThat(num(invoice.body(), "tax")).isEqualTo(1501);
    }

    @Test
    void lateCommittedActivityCarriesForwardWithoutChangingAnIssuedInvoice() {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        TestMerchant merchant = createMerchant(ALPHA);
        Response plan = plan(merchant, clock.instant(), 10, 100, 0);
        clock.set(Instant.parse("2026-10-31T18:30:00Z"));
        Response original = issue(merchant, "2026-10");
        assertThat(num(original.body(), "total")).isZero();
        Instant serviceDate = Instant.parse("2026-10-31T18:29:59Z");
        tx.executeWithoutResult(status -> repository.insertEvent(new BillingEvent("bev_late", merchant.id(),
                com.payments.gateway.shared.events.BillableActivity.Kind.PAYMENT, "late_attempt", "late_payment",
                str(plan.body(), "id"), com.payments.gateway.shared.model.MethodType.CARD,
                com.payments.gateway.shared.model.Money.of(10_000, "INR"), 10_000L, 110L, null, serviceDate)));
        assertThat(issue(merchant, "2026-10").raw()).isEqualTo(original.raw());
        clock.set(Instant.parse("2026-11-30T18:30:00Z"));
        Response next = issue(merchant, "2026-11");
        assertThat(next.status()).as(next.raw()).isEqualTo(201);
        assertThat(num(next.body(), "subtotal")).isEqualTo(110);
        assertThat(str(list(next.body(), "lines").getFirst(), "first_activity_at")).isEqualTo(serviceDate.toString());
    }

    @Test
    void minimumAppliesWithoutActivityAndNumberingUsesIssueDateFinancialYear() {
        clock.set(Instant.parse("2027-03-31T18:29:00Z"));
        TestMerchant merchant = createMerchant();
        plan(merchant, clock.instant(), 0, 0, 25);
        plan(merchant, Instant.parse("2027-03-31T18:30:00Z"), 0, 0, 1000);
        clock.set(Instant.parse("2027-03-31T18:30:00Z"));
        Response invoice = issue(merchant, "2027-03");
        assertThat(invoice.status()).as(invoice.raw()).isEqualTo(201);
        assertThat(str(invoice.body(), "number")).isEqualTo("INV/2027/000001");
        assertThat(num(invoice.body(), "total")).isEqualTo(30);
        assertThat(list(invoice.body(), "lines")).singleElement().satisfies(line -> {
            assertThat(str(line, "kind")).isEqualTo("MINIMUM");
            assertThat(num(line, "amount")).isEqualTo(25);
            assertThat(num(line, "tax")).isEqualTo(5);
        });
    }

    @Test
    void duplicatePspCapturesDoNotBecomeAdditionalSuccessfulServices() {
        TestMerchant merchant = createMerchant(ALPHA);
        plan(merchant, clock.instant(), 100, 0, 0);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
        Response first = confirm(merchant, paymentId, card());
        simulate(ALPHA, str(first.body(), "latest_attempt.provider_reference"), "failure", false);
        Response second = confirm(merchant, paymentId, card());
        simulate(ALPHA, str(second.body(), "latest_attempt.provider_reference"), "success", false);
        outcomes.apply(paymentId, str(first.body(), "latest_attempt.id"), new AttemptUpdate(AttemptStatus.SUCCEEDED,
                str(first.body(), "latest_attempt.provider_reference"), null, null, Money.of(10_000, "INR")),
                TransitionSource.PROVIDER_WEBHOOK);
        assertThat(count("SELECT count(*) FROM payment_attempts WHERE status = 'SUCCEEDED'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM billing_events")).isEqualTo(1);
        assertThat(repository.events(merchant.id(), 10).getFirst().referenceId()).isEqualTo(str(second.body(), "latest_attempt.id"));
    }

    @Test
    void acceptedLateSuccessIsABillableService() {
        TestMerchant merchant = createMerchantWith(null, "accept", ALPHA);
        plan(merchant, clock.instant(), 100, 0, 0);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, card());
        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireDue();
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        assertThat(getPayment(merchant, paymentId)).containsEntry("status", "succeeded");
        assertThat(count("SELECT count(*) FROM billing_events")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM refunds")).isZero();
    }

    @Test
    void activityCommittedDuringIssueIsCarriedForwardFromAStableSnapshot() {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        TestMerchant merchant = createMerchant(ALPHA);
        Response published = plan(merchant, clock.instant(), 10, 0, 0);
        payCard(merchant, 10_000, "INR");
        Instant serviceDate = clock.instant();
        clock.set(Instant.parse("2026-10-31T18:30:00Z"));
        BillingRepository target = AopTestUtils.getUltimateTargetObject(repository);
        AtomicBoolean insertOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object grouped = invocation.callRealMethod();
            if (insertOnce.getAndSet(false)) {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    executor.submit(() -> tx.executeWithoutResult(status -> repository.insertEvent(new BillingEvent(
                            "bev_during_issue", merchant.id(), com.payments.gateway.shared.events.BillableActivity.Kind.PAYMENT,
                            "during_issue", "payment_during_issue", str(published.body(), "id"),
                            com.payments.gateway.shared.model.MethodType.CARD, Money.of(10_000, "INR"), 10_000L,
                            10L, null, serviceDate)))).get(5, TimeUnit.SECONDS);
                }
            }
            return grouped;
        }).when(target).charges(anyString(), any(Instant.class));
        Response first = issue(merchant, "2026-10");
        assertThat(first.status()).as(first.raw()).isEqualTo(201);
        assertThat(num(first.body(), "subtotal")).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM billing_events")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM billing_event_assignments")).isEqualTo(1);
        clock.set(Instant.parse("2026-11-30T18:30:00Z"));
        Response next = issue(merchant, "2026-11");
        assertThat(next.status()).as(next.raw()).isEqualTo(201);
        assertThat(num(next.body(), "subtotal")).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM billing_event_assignments")).isEqualTo(2);
    }

        @Test
        void databaseRejectsForgedTotalsExcessCreditsAndAdditionalIssuedDocumentLines() {
        clock.set(Instant.parse("2026-10-10T00:00:00Z"));
        TestMerchant merchant = createMerchant(ALPHA);
        Response published = plan(merchant, clock.instant(), 101, 0, 202);
        payCard(merchant, 10_000, "INR");
        clock.set(Instant.parse("2026-10-31T18:30:00Z"));
        Response issued = issue(merchant, "2026-10");
        String invoiceId = str(issued.body(), "id");
        BillingDocuments.Invoice original = repository.invoice(merchant.id(), invoiceId).orElseThrow();
        BillingDocuments.InvoiceLine originalLine = original.lines().getFirst();
        repository.insertEvent(new BillingEvent("bev_forged", merchant.id(), BillableActivity.Kind.PAYMENT, "forged_attempt",
            "forged_payment", str(published.body(), "id"), com.payments.gateway.shared.model.MethodType.CARD,
            Money.of(10_000, "INR"), 10_000L, 101L, null, clock.instant()));
        BillingDocuments.Invoice forged = new BillingDocuments.Invoice("binv_forged", "billing_invoice", merchant.id(),
            "INV/2026/000099", YearMonth.of(2026, 11), "INR", 201, 36, 237, "MERCHANT_PAYMENT", clock.instant(),
            List.of(new BillingDocuments.InvoiceLine("bil_forged", "PAYMENT", originalLine.planId(), originalLine.method(),
                1, 101, 18, clock.instant(), clock.instant()),
                new BillingDocuments.InvoiceLine("bil_forged_min", "MINIMUM", originalLine.planId(), null,
                    1, 101, 18, null, null)));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.insertInvoice(forged, str(published.body(), "id"), 2026, 99)))
            .hasStackTraceContaining("invoice totals or minimum do not match");
        for (long[] values : new long[][] {{102, 18}, {40, 19}, {1, 2}}) {
            BillingDocuments.CreditNote bad = new BillingDocuments.CreditNote("bcn_bad", "billing_credit_note", merchant.id(),
                invoiceId, "bad", "Bad credit", "CN/2026/000099", "INR", values[0], values[1], values[0] + values[1],
                clock.instant(), List.of(new BillingDocuments.CreditLine("bcl_bad", originalLine.id(), values[0], values[1])));
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.insertCredit(bad, 2026, 99)))
                .hasStackTraceContaining("credit exceeds the original invoice line");
        }
        assertThatThrownBy(() -> jdbc.sql("""
            INSERT INTO billing_invoice_lines (id, invoice_id, merchant_id, position, kind, plan_id, method, quantity,
                                amount, tax, first_activity_at, last_activity_at)
                SELECT 'appended', invoice_id, merchant_id, position + 100, kind, plan_id, method, quantity,
                  amount, tax, first_activity_at, last_activity_at FROM billing_invoice_lines WHERE kind = 'PAYMENT'
            """).update()).hasMessageContaining("issued billing invoice is immutable");
        Response note = credit(merchant, invoiceId, "valid", originalLine.id(), 40);
        assertThat(note.status()).as(note.raw()).isEqualTo(201);
        assertThatThrownBy(() -> jdbc.sql("""
            INSERT INTO billing_credit_lines (id, credit_note_id, invoice_id, merchant_id, invoice_line_id, amount, tax)
                                SELECT 'appended', note.id, note.invoice_id, note.merchant_id, line.id, 1, 0
                                    FROM billing_credit_notes note JOIN billing_invoice_lines line ON line.invoice_id = note.invoice_id
                                 WHERE line.kind = 'MINIMUM'
            """).update()).hasMessageContaining("issued billing credit note is immutable");
        }

        @Test
        void replayingAServiceEventCannotChangeItsPlanPriceOrRecognitionTime() {
            TestMerchant merchant = createMerchant(ALPHA);
            plan(merchant, clock.instant(), 100, 0, 0);
            payCard(merchant, 10_000, "INR");
            BillingEvent original = repository.events(merchant.id(), 10).getFirst();
            clock.advance(Duration.ofDays(1));
            plan(merchant, clock.instant(), 900, 0, 0);
            tx.executeWithoutResult(status -> events.publishEvent(new BillableActivity(merchant.id(), original.kind(), original.referenceId(),
                    original.paymentId(), original.method(), original.amount(), Money.of(original.settledAmount(), "INR"), clock.instant())));
            assertThat(repository.events(merchant.id(), 10)).containsExactly(original);
        }

        @Test
        void exhaustedNumberSeriesFailsWithoutIssuingOrAdvancingTheMonth() {
            clock.set(Instant.parse("2026-10-10T00:00:00Z"));
            TestMerchant merchant = createMerchant(ALPHA);
            plan(merchant, clock.instant(), 100, 0, 0);
            clock.set(Instant.parse("2026-10-31T18:30:00Z"));
            jdbc.sql("INSERT INTO billing_series (series, financial_year, last_value) VALUES ('INV', 2026, :maximum)")
                    .param("maximum", BillingAmounts.MAX_SEQUENCE).update();
            Response response = issue(merchant, "2026-10");
            assertThat(response.status()).as(response.raw()).isEqualTo(409);
            assertThat(response.body()).containsEntry("code", "billing_invalid_state");
            assertThat(count("SELECT count(*) FROM billing_invoices")).isZero();
            assertThat(count("SELECT last_value FROM billing_series")).isEqualTo(BillingAmounts.MAX_SEQUENCE);
            YearMonth nextMonth = tx.execute(status -> repository.lockAccount(merchant.id()).orElseThrow().nextMonth());
            assertThat(nextMonth).isEqualTo(YearMonth.of(2026, 10));
        }

        @Test
        void workerCatchUpIsBoundedAndContinuesFromTheNextUnissuedMonth() {
            clock.set(Instant.parse("2026-07-10T00:00:00Z"));
            TestMerchant merchant = createMerchant();
            plan(merchant, clock.instant(), 100, 0, 25);
            clock.set(Instant.parse("2026-11-30T18:30:00Z"));
            BillingJob job = new BillingJob(repository, billing, new BillingProperties(1, 2), clock);
            assertThat(job.run()).isEqualTo(new BillingJob.RunResult(1, 2, 0));
            assertThat(count("SELECT count(*) FROM billing_invoices")).isEqualTo(2);
            assertThat(job.run()).isEqualTo(new BillingJob.RunResult(1, 2, 0));
            assertThat(job.run()).isEqualTo(new BillingJob.RunResult(1, 1, 0));
            assertThat(job.run()).isEqualTo(new BillingJob.RunResult(0, 0, 0));
            assertThat(repository.invoices(merchant.id())).extracting(BillingDocuments.Invoice::month)
                    .containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 10), YearMonth.of(2026, 9), YearMonth.of(2026, 8), YearMonth.of(2026, 7));
        }

        @Test
        void successiveSmallCreditsAllocateTaxFromTheRunningTotal() {
            clock.set(Instant.parse("2026-10-10T00:00:00Z"));
            TestMerchant merchant = createMerchant(ALPHA);
            plan(merchant, clock.instant(), 3, 0, 0);
            payCard(merchant, 10_000, "INR");
            clock.set(Instant.parse("2026-10-31T18:30:00Z"));
            Response invoice = issue(merchant, "2026-10");
            String invoiceId = str(invoice.body(), "id");
            String lineId = str(list(invoice.body(), "lines").getFirst(), "id");
            assertThat(num(invoice.body(), "tax")).isEqualTo(1);
            long[] expectedTaxes = {0, 1, 0};
            for (int index = 0; index < expectedTaxes.length; index++) {
                Response note = credit(merchant, invoiceId, "small-" + index, lineId, 1);
                assertThat(note.status()).as(note.raw()).isEqualTo(201);
                assertThat(num(note.body(), "tax")).isEqualTo(expectedTaxes[index]);
            }
            assertThat(count("SELECT sum(tax) FROM billing_credit_notes")).isEqualTo(1);
            assertThat(count("SELECT sum(subtotal) FROM billing_credit_notes")).isEqualTo(3);
        }

        private List<Response> concurrently(TestMerchant merchant, java.util.concurrent.Callable<Response> action) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Response>> pending = tx.execute(status -> {
                repository.lockAccount(merchant.id()).orElseThrow();
                List<Future<Response>> futures = List.of(executor.submit(action), executor.submit(action));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                          AND wait_event_type = 'Lock' AND query ILIKE '%FROM billing_accounts%FOR UPDATE%'
                        """)).isGreaterThanOrEqualTo(2));
                return futures;
            });
            return List.of(pending.getFirst().get(10, TimeUnit.SECONDS), pending.getLast().get(10, TimeUnit.SECONDS));
        }
    }

    private Response credit(TestMerchant merchant, String invoiceId, String reference, String lineId, long amount) {
        return admin("POST", path(merchant) + "/invoices/" + invoiceId + "/credit-notes",
                Map.of("reference", reference, "reason", "Contract correction", "lines", java.util.List.of(
                        Map.of("invoice_line_id", lineId, "amount", amount))));
    }

    private Response issue(TestMerchant merchant, String month) {
        return admin("POST", path(merchant) + "/invoices", Map.of("month", month));
    }

    private Map<String, Object> payCard(TestMerchant merchant, long amount, String currency) {
        Response created = post(merchant, "/v1/payments", UUID.randomUUID().toString(), Map.of("amount", amount,
                "currency", currency, "merchant_order_id", UUID.randomUUID().toString()));
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        String paymentId = str(created.body(), "id");
        Response confirmed = confirm(merchant, paymentId, card());
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        Map<String, Object> result = getPayment(merchant, paymentId);
        assertThat(result).containsEntry("status", "succeeded");
        return result;
    }

    private Response plan(TestMerchant merchant, Instant effectiveAt, long fixed, int rate, long minimum) {
        return admin("POST", path(merchant) + "/billing-plans", Map.of("name", "Standard", "effective_at", effectiveAt.toString(),
                "payment_fees", Map.of("card", fee(fixed, rate)), "monthly_minimum", minimum));
    }

    private static Map<String, Object> fee(long fixed, int rate) {
        return Map.of("fixed_amount", fixed, "rate_bps", rate);
    }

    private static String path(TestMerchant merchant) {
        return "/admin/v1/merchants/" + merchant.id();
    }
}