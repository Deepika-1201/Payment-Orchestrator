package com.payments.gateway.routing;

import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.platform.RetentionJob;
import com.payments.gateway.reconciliation.ReconciliationService;
import com.payments.gateway.support.IntegrationTest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

class AdvancedRoutingIntegrationTest extends IntegrationTest {

    @Autowired
    private ProviderFeeService fees;
        @Autowired
        private RetentionJob retention;
        @Autowired
        private ReconciliationService reconciliation;
        @MockitoSpyBean
        private RoutingDecisionRepository decisions;

    @Test
    void feeSchedulesAreEffectiveDatedImmutableAndAccountScoped() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);
        TestMerchant other = createMerchant(ALPHA);
        Instant firstAt = clock.instant();
        Response first = publish(merchant, ALPHA, "INR", 17, 125, firstAt);
        assertThat(first.status()).as(first.raw()).isEqualTo(201);
        assertThat(num(first.body(), "tax_bps")).isEqualTo(1800);
        Instant later = firstAt.plus(Duration.ofDays(1));
        Response next = publish(merchant, ALPHA, "INR", 23, 200, later);
        assertThat(next.status()).isEqualTo(201);
        assertThat(fees.effective(merchant.id(), Set.of(ALPHA, BETA), MethodType.CARD, "INR", firstAt))
                .hasSize(1).containsKey(ALPHA);
        assertThat(fees.effective(merchant.id(), Set.of(ALPHA), MethodType.CARD, "INR", later.minusNanos(1000)).get(ALPHA).id())
                .isEqualTo(str(first.body(), "id"));
        assertThat(fees.effective(merchant.id(), Set.of(ALPHA), MethodType.CARD, "INR", later).get(ALPHA).id())
                .isEqualTo(str(next.body(), "id"));
        assertThat(fees.effective(other.id(), Set.of(ALPHA), MethodType.CARD, "INR", later)).isEmpty();
        assertThat(fees.effective(merchant.id(), Set.of(BETA), MethodType.CARD, "INR", later)).isEmpty();
        assertThat(fees.effective(merchant.id(), Set.of(ALPHA), MethodType.CARD, "USD", later)).isEmpty();
        assertThat(fees.effective(merchant.id(), Set.of(ALPHA), MethodType.UPI, "INR", later)).isEmpty();
        assertThat(publish(merchant, ALPHA, "INR", 50, 100, later).status()).isEqualTo(409);
        assertThat(publish(merchant, ALPHA, "INR", 50, 100, firstAt.minusSeconds(1)).status()).isEqualTo(400);
        assertThatThrownBy(() -> jdbc.sql("UPDATE provider_fee_schedules SET fixed_amount = 999").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM provider_fee_schedules").update()).hasMessageContaining("append-only");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'provider_fee_schedule.published'")).isEqualTo(2);
        assertThat(list(admin("GET", feePath(merchant, ALPHA), null).body(), "data")).hasSize(2);
        admin("POST", "/admin/v1/merchants/" + merchant.id() + "/provider-accounts/" + ALPHA + "/disable", null);
        assertThat(list(admin("GET", feePath(merchant, ALPHA), null).body(), "data")).hasSize(2);
    }

    @Test
    void publishingFeesRequiresFinancePermissionAndACompatibleLinkedAccount() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> body = Map.of("method", "card", "currency", "INR", "fixed_amount", 0, "rate_bps", 200);
        assertThat(send("POST", feePath(merchant, ALPHA), Map.of("Authorization", "Bearer test-ops-token"), body).status()).isEqualTo(403);
        assertThat(send("POST", feePath(merchant, ALPHA), Map.of("Authorization", "Bearer test-readonly-token"), body).status()).isEqualTo(403);
        assertThat(send("POST", feePath(merchant, ALPHA), Map.of("Authorization", "Bearer test-finance-token"), body).status()).isEqualTo(201);
        assertThat(publish(merchant, BETA, "INR", 0, 200, clock.instant()).status()).isEqualTo(404);
        assertThat(admin("POST", feePath(merchant, ALPHA), Map.of("method", "upi", "currency", "USD", "fixed_amount", 0)).status())
                .isEqualTo(400);
        assertThat(admin("POST", feePath(merchant, ALPHA), Map.of("method", "card", "currency", "INR", "fixed_amount", -1)).status())
                .isEqualTo(400);
        assertThat(admin("POST", feePath(merchant, ALPHA), Map.of("method", "card", "currency", "INR", "fixed_amount", 0, "rate_bps", 10001)).status())
                .isEqualTo(400);
    }

    @Test
    void aShadowDecisionIsStoredWithTheLiveAttemptButNeverChangesThePspOrPreviewState() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);
        publish(merchant, ALPHA, "INR", 300, 0, clock.instant());
        publish(merchant, BETA, "INR", 100, 0, clock.instant());
        Response rule = routingRule(merchant, "priority", "cost");
        assertThat(rule.status()).as(rule.raw()).isEqualTo(201);
        Map<String, Object> previewBody = Map.of("merchant_id", merchant.id(), "method", "card", "amount", 10_000, "currency", "INR");
        Response preview = send("POST", "/admin/v1/routing/preview", Map.of("Authorization", "Bearer test-readonly-token"), previewBody);
        assertThat(preview.status()).as(preview.raw()).isEqualTo(200);
        assertThat(preview.body().get("providers")).isEqualTo(List.of(ALPHA, BETA));
        assertThat(str(preview.body(), "shadow.strategy")).isEqualToIgnoringCase("cost");
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
        Response checkout = post(merchant, "/v1/checkout-sessions", UUID.randomUUID().toString(),
                Map.of("payment_id", paymentId, "return_url", "https://merchant.example/return"));
        assertThat(send("GET", URI.create(str(checkout.body(), "url")).getPath(), Map.of(), null).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM routing_decisions")).isZero();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("payment_method", card());
        Response confirmed = post(merchant, "/v1/payments/" + paymentId + "/confirm", key, body);
        assertThat(str(confirmed.body(), "latest_attempt.provider")).isEqualTo(ALPHA);
        assertThat(post(merchant, "/v1/payments/" + paymentId + "/confirm", key, body).raw()).isEqualTo(confirmed.raw());
        assertThat(count("SELECT count(*) FROM routing_decisions")).isEqualTo(1);
        Map<String, Object> recorded = list(admin("GET", "/admin/v1/routing-decisions?merchant_id=" + merchant.id(), null).body(), "data").getFirst();
        assertThat(str(recorded, "chosen_provider")).isEqualTo(ALPHA);
        assertThat(recorded.get("attempt_id")).isEqualTo(str(confirmed.body(), "latest_attempt.id"));
        assertThat(count("SELECT count(*) FROM routing_decisions WHERE shadow_provider = 'MOCK_BETA'")).isEqualTo(1);
        assertThat(mockProviders.stream().filter(provider -> provider.code().equals(BETA)).findFirst().orElseThrow().psp()
                .find(null, str(confirmed.body(), "latest_attempt.id"))).isEmpty();
        assertThatThrownBy(() -> jdbc.sql("UPDATE routing_decisions SET chosen_provider = 'forged'").update())
                .hasMessageContaining("append-only");
        clock.advance(Duration.ofDays(90));
        assertThat(retention.run().get("routing_decisions")).isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(retention.run().get("routing_decisions")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM provider_fee_schedules")).isEqualTo(2);
    }

    @Test
    void costRulesUsePublishedSchedulesAndUnknownShadowStrategiesAreRejected() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);
        publish(merchant, ALPHA, "INR", 300, 0, clock.instant());
        publish(merchant, BETA, "INR", 100, 0, clock.instant());
        assertThat(routingRule(merchant, "cost", "priority").status()).isEqualTo(400);
        assertThat(routingRule(merchant, "cost", "adaptive").status()).isEqualTo(201);
        Response confirmed = confirm(merchant, str(createPayment(merchant, 10_000, "automatic"), "id"), card());
        assertThat(str(confirmed.body(), "latest_attempt.provider")).isEqualTo(BETA);
        assertThat(count("SELECT count(*) FROM routing_decisions WHERE live_strategy = 'COST' AND shadow_strategy = 'ADAPTIVE'"))
                .isEqualTo(1);
    }

        @Test
        void feeComparisonUsesCaptureTimeAndNeverReplacesTheActualLedgerFee() {
                TestMerchant merchant = createMerchant(ALPHA);
                price(merchant, "INR", 0, 200, 0, "withheld");
                payCard(merchant, 10_000, "INR");
                clock.advance(Duration.ofHours(1));
                Response changed = price(merchant, "INR", 0, 300, 0, "withheld");
                payCard(merchant, 10_000, "INR");

                ReconciliationService.RunSummary run = reconcile(merchant);
                assertThat(run.linesMatched()).isEqualTo(2);
                assertThat(run.exceptions()).singleElement().satisfies(exception -> {
                        assertThat(exception.type()).isEqualTo("fee_schedule_mismatch");
                        assertThat(exception.expectedAmount()).isEqualTo(300);
                        assertThat(exception.actualAmount()).isEqualTo(200);
                        assertThat(exception.details()).contains(str(changed.body(), "id"));
                });
                assertThat(ledgerBalances(merchant)).containsEntry("psp_fees", 400L).containsEntry("psp_receivable", 0L);
                reconcile(merchant);
                assertThat(count("SELECT count(*) FROM reconciliation_exceptions WHERE type = 'FEE_SCHEDULE_MISMATCH'"))
                                .isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(ints = {198, 199, 201, 202})
        void feeComparisonAllowsOneSettlementMinorUnitOfTolerance(int expected) {
                TestMerchant merchant = createMerchant(ALPHA);
                price(merchant, "INR", expected, 0, 0, "withheld");
                payCard(merchant, 10_000, "INR");
                ReconciliationService.RunSummary run = reconcile(merchant);
                assertThat(run.linesMatched()).isEqualTo(1);
                assertThat(run.exceptions()).hasSize(Math.abs(expected - 200) > 1 ? 1 : 0);
                assertThat(ledgerBalances(merchant)).containsEntry("psp_fees", 200L).containsEntry("psp_receivable", 0L);
        }

        @Test
        void zeroWithholdingOnABankTransferCreditIsStillComparedToItsFeeSchedule() {
                TestMerchant merchant = createMerchant(ALPHA);
                Response schedule = admin("POST", feePath(merchant, ALPHA), Map.of("method", "bank_transfer", "currency", "INR",
                                "fixed_amount", 3, "rate_bps", 0, "tax_bps", 0, "collection", "withheld"));
                assertThat(schedule.status()).as(schedule.raw()).isEqualTo(201);
                String paymentId = str(createPayment(merchant, 100, "automatic"), "id");
                Response confirmed = confirm(merchant, paymentId, Map.of("type", "bank_transfer"));
                String reference = str(confirmed.body(), "latest_attempt.provider_reference");
                Response credited = send("POST", "/simulator/" + ALPHA + "/collections/" + reference + "/credits", Map.of(),
                                Map.of("amount", 1, "mode", "neft"));
                assertThat(credited.status()).as(credited.raw()).isEqualTo(200);

                ReconciliationService.RunSummary run = reconcile(merchant);
                assertThat(run.linesMatched()).isEqualTo(1);
                assertThat(run.feeAmount()).isZero();
                assertThat(run.settledAmount()).isEqualTo(1);
                assertThat(run.exceptions()).singleElement().satisfies(exception -> {
                        assertThat(exception.type()).isEqualTo("fee_schedule_mismatch");
                        assertThat(exception.expectedAmount()).isEqualTo(3);
                        assertThat(exception.actualAmount()).isZero();
                });
                assertThat(ledgerBalances(merchant)).containsEntry("psp_receivable", 0L).doesNotContainKey("psp_fees");
        }

        @Test
        void postpaidSchedulesFlagUnexpectedWithholdingWithoutChangingThePayout() {
                TestMerchant merchant = createMerchant(ALPHA);
                price(merchant, "INR", 30, 200, 1800, "postpaid");
                payCard(merchant, 10_000, "INR");
                ReconciliationService.RunSummary run = reconcile(merchant);
                assertThat(run.linesMatched()).isEqualTo(1);
                assertThat(run.exceptions()).singleElement().satisfies(exception -> {
                        assertThat(exception.type()).isEqualTo("fee_schedule_mismatch");
                        assertThat(exception.expectedAmount()).isZero();
                        assertThat(exception.actualAmount()).isEqualTo(200);
                });
                assertThat(run.settledAmount()).isEqualTo(9800);
        }

        @Test
        void foreignFeeSchedulesAreComparedInReportedInr() {
                TestMerchant merchant = createMerchant(ALPHA);
                admin("PATCH", "/admin/v1/merchants/" + merchant.id(), Map.of("international_cards", true));
                price(merchant, "USD", 30, 200, 1800, "withheld");
                payCard(merchant, 10_000, "USD");
                ReconciliationService.RunSummary run = reconcile(merchant);
                assertThat(run.linesMatched()).isEqualTo(1);
                assertThat(run.exceptions()).singleElement().satisfies(exception -> {
                        assertThat(exception.type()).isEqualTo("fee_schedule_mismatch");
                        assertThat(exception.expectedAmount()).isEqualTo(22_595);
                        assertThat(exception.actualAmount()).isEqualTo(16_650);
                        assertThat(exception.details()).contains("INR");
                });
                assertThat(ledgerBalances(merchant)).containsEntry("psp_fees", 16_650L).containsEntry("psp_receivable", 0L);
        }

        @Test
        void historyAndAttemptRollBackTogetherBeforeAnyPspCall() {
                TestMerchant merchant = createMerchant(ALPHA, BETA);
                routingRule(merchant, "adaptive", "cost");
                String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
                RoutingDecisionRepository target = AopTestUtils.getUltimateTargetObject(decisions);
                doAnswer(invocation -> {
                        invocation.callRealMethod();
                        throw new IllegalStateException("Simulated failure after recording routing history");
                }).when(target).append(anyString(), anyString(), any(PaymentMethod.class), any(RoutingDecision.class), any(Instant.class));
                Response failed = confirm(merchant, paymentId, card());
                assertThat(failed.status()).isEqualTo(500);
                assertThat(count("SELECT count(*) FROM routing_decisions")).isZero();
                assertThat(count("SELECT count(*) FROM payment_attempts WHERE payment_id = ?", paymentId)).isZero();
                assertThat(getPayment(merchant, paymentId)).containsEntry("status", "requires_payment_method");
                assertThat(health.prediction(ALPHA, MethodType.CARD, null).samples()).isZero();
        }

        @Test
        void committedPaymentOutcomesFeedTheBankSpecificPrediction() {
                TestMerchant merchant = createMerchant(ALPHA);
                for (int index = 0; index < 19; index++) {
                        health.recordAttemptOutcome(ALPHA, MethodType.NETBANKING, "HDFC", true, null);
                }
                String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");
                Response confirmed = confirm(merchant, paymentId, netbanking("HDFC"));
                assertThat(health.prediction(ALPHA, MethodType.NETBANKING, "HDFC").bankSpecific()).isFalse();
                simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
                assertThat(health.prediction(ALPHA, MethodType.NETBANKING, "HDFC").bankSpecific()).isTrue();
                assertThat(health.prediction(ALPHA, MethodType.NETBANKING, "HDFC").successes()).isEqualTo(20);
        }

            @ParameterizedTest
            @EnumSource(value = RoutingStrategy.class, names = {"COST", "ADAPTIVE"})
            void advancedRulesWithoutAShadowStillKeepMerchantScopedHistory(RoutingStrategy strategy) {
                TestMerchant merchant = createMerchant(ALPHA);
                TestMerchant other = createMerchant(ALPHA);
                price(merchant, "INR", 0, 200, 0, "withheld");
                Response rule = admin("POST", "/admin/v1/routing-rules", Map.of("merchant_id", merchant.id(), "name", "Live only",
                        "priority", 1, "strategy", strategy.name(), "targets", List.of(Map.of("provider", ALPHA))));
                assertThat(rule.status()).as(rule.raw()).isEqualTo(201);
                confirm(merchant, str(createPayment(merchant, 10_000, "automatic"), "id"), card());
                List<Map<String, Object>> history = list(admin("GET", "/admin/v1/routing-decisions?merchant_id=" + merchant.id(), null).body(), "data");
                assertThat(history).singleElement().satisfies(row -> {
                    assertThat(str(row, "live.strategy")).isEqualToIgnoringCase(strategy.name());
                    assertThat(row).doesNotContainKey("shadow");
                });
                assertThat(list(admin("GET", "/admin/v1/routing-decisions?merchant_id=" + other.id(), null).body(), "data")).isEmpty();
                assertThat(admin("GET", "/admin/v1/routing-decisions?merchant_id=" + merchant.id() + "&limit=0", null).status()).isEqualTo(400);
                assertThat(admin("GET", "/admin/v1/routing-decisions?merchant_id=" + merchant.id() + "&limit=501", null).status()).isEqualTo(400);
            }

            @Test
            void feeScheduleSchemaRejectsInvalidFinancialConfiguration() {
                TestMerchant merchant = createMerchant(ALPHA);
                price(merchant, "INR", 0, 200, 0, "withheld");
                List<String> columns = List.of("merchant_id", "provider_code", "method", "currency", "fixed_amount", "rate_bps",
                        "tax_bps", "collection", "effective_at", "created_at");
                List<Map<String, String>> invalid = List.of(Map.of("fixed_amount", "-1"), Map.of("fixed_amount", "1000000000001"),
                        Map.of("rate_bps", "-1"), Map.of("rate_bps", "10001"), Map.of("tax_bps", "-1"), Map.of("tax_bps", "10001"),
                        Map.of("currency", "'usd'"), Map.of("method", "'OTHER'"), Map.of("collection", "'OTHER'"),
                        Map.of("effective_at", "created_at - interval '1 second'"));
                for (Map<String, String> fields : invalid) {
                    String selected = columns.stream().map(column -> fields.getOrDefault(column,
                            column.equals("effective_at") ? "effective_at + interval '1 second'" : column))
                            .collect(java.util.stream.Collectors.joining(", "));
                    assertThatThrownBy(() -> jdbc.sql("INSERT INTO provider_fee_schedules (id, " + String.join(", ", columns)
                            + ") SELECT 'invalid', " + selected + " FROM provider_fee_schedules").update())
                            .as(fields.toString()).hasMessageContaining("check constraint");
                }
                assertThat(count("SELECT count(*) FROM provider_fee_schedules")).isEqualTo(1);
                TestMerchant unlinked = createMerchant(BETA);
                assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO provider_fee_schedules (id, merchant_id, provider_code, method, currency, fixed_amount,
                                                             rate_bps, tax_bps, collection, effective_at, created_at)
                        SELECT 'unlinked', :merchant, provider_code, method, currency, fixed_amount,
                               rate_bps, tax_bps, collection, effective_at, created_at FROM provider_fee_schedules
                        """).param("merchant", unlinked.id()).update()).hasMessageContaining("fk_fee_schedule_account");
            }

            @Test
            void aShadowStrategyCanBePromotedAndThenRemovedOnTheExistingRule() {
                TestMerchant merchant = createMerchant(ALPHA, BETA);
                publish(merchant, ALPHA, "INR", 300, 0, clock.instant());
                publish(merchant, BETA, "INR", 100, 0, clock.instant());
                Response created = routingRule(merchant, "priority", "cost");
                String ruleId = str(created.body(), "id");
                List<Map<String, Object>> targets = List.of(Map.of("provider", ALPHA), Map.of("provider", BETA));
                Response promoted = admin("PUT", "/admin/v1/routing-rules/" + ruleId,
                        Map.of("merchant_id", merchant.id(), "name", "Promoted", "priority", 1, "strategy", "cost",
                                "shadow_strategy", "adaptive", "targets", targets, "version", num(created.body(), "version")));
                assertThat(promoted.status()).as(promoted.raw()).isEqualTo(200);
                assertThat(str(promoted.body(), "shadow_strategy")).isEqualToIgnoringCase("adaptive");
                Response chosen = confirm(merchant, str(createPayment(merchant, 10_000, "automatic"), "id"), card());
                assertThat(str(chosen.body(), "latest_attempt.provider")).isEqualTo(BETA);
                Response removed = admin("PUT", "/admin/v1/routing-rules/" + ruleId,
                        Map.of("merchant_id", merchant.id(), "name", "No shadow", "priority", 1, "strategy", "priority",
                                "targets", targets, "version", num(promoted.body(), "version")));
                assertThat(removed.status()).as(removed.raw()).isEqualTo(200);
                assertThat(removed.body()).doesNotContainKey("shadow_strategy");
                Response preview = admin("POST", "/admin/v1/routing/preview", Map.of("merchant_id", merchant.id(),
                        "method", "card", "amount", 10_000, "currency", "INR"));
                assertThat(preview.body().get("providers")).isEqualTo(List.of(ALPHA, BETA));
                assertThat(preview.body()).doesNotContainKey("shadow");
            }

        @Test
        void openingAdaptiveCheckoutDoesNotConsumeLiveOrShadowExplorationSlots() {
                TestMerchant merchant = createMerchant(ALPHA);
                admin("PATCH", "/admin/v1/merchants/" + merchant.id(), Map.of("international_cards", true));
                routingRule(merchant, "adaptive", "adaptive");
                Response payment = post(merchant, "/v1/payments", UUID.randomUUID().toString(), Map.of("amount", 10_000,
                                "currency", "USD", "merchant_order_id", UUID.randomUUID().toString()));
                assertThat(payment.status()).as(payment.raw()).isEqualTo(201);
                String paymentId = str(payment.body(), "id");
                Response session = post(merchant, "/v1/checkout-sessions", UUID.randomUUID().toString(),
                                Map.of("payment_id", paymentId, "return_url", "https://merchant.example/return"));
                String path = URI.create(str(session.body(), "url")).getPath();
                for (int index = 0; index < 9; index++) {
                        assertThat(send("GET", path, Map.of(), null).status()).isEqualTo(200);
                }
                assertThat(count("SELECT count(*) FROM routing_decisions")).isZero();
                assertThat(confirm(merchant, paymentId, card()).status()).isEqualTo(200);
                RoutingDecisionRepository.RecordedDecision recorded = decisions.recent(merchant.id(), 1).getFirst();
                assertThat(recorded.live().exploration()).isFalse();
                assertThat(recorded.shadow().exploration()).isFalse();
        }

        private Response price(TestMerchant merchant, String currency, long fixed, int rate, int tax, String collection) {
                Response response = admin("POST", feePath(merchant, ALPHA), Map.of("method", "card", "currency", currency,
                                "fixed_amount", fixed, "rate_bps", rate, "tax_bps", tax, "collection", collection));
                assertThat(response.status()).as(response.raw()).isEqualTo(201);
                return response;
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

        private ReconciliationService.RunSummary reconcile(TestMerchant merchant) {
                return reconciliation.run(merchant.id(), ALPHA, clock.instant().minus(Duration.ofDays(1)), clock.instant().plusSeconds(1));
        }

        private Response routingRule(TestMerchant merchant, String strategy, String shadow) {
        return admin("POST", "/admin/v1/routing-rules", Map.of("merchant_id", merchant.id(), "name", "Test route",
                "priority", 1, "strategy", strategy, "shadow_strategy", shadow, "allow_fallback", false,
                "targets", List.of(Map.of("provider", ALPHA, "weight", 1), Map.of("provider", BETA, "weight", 1))));
    }

    private Response publish(TestMerchant merchant, String provider, String currency, long fixed, int rate, Instant effectiveAt) {
        return admin("POST", feePath(merchant, provider), Map.of("method", "card", "currency", currency,
                "fixed_amount", fixed, "rate_bps", rate, "effective_at", effectiveAt.toString()));
    }

    private static String feePath(TestMerchant merchant, String provider) {
        return "/admin/v1/merchants/" + merchant.id() + "/provider-accounts/" + provider + "/fee-schedules";
    }
}