package com.payments.gateway.routing;

import com.payments.gateway.provider.MerchantAccountResolver;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderHealthTracker;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.InboundWebhook;
import com.payments.gateway.provider.spi.InitiatePaymentRequest;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.provider.spi.ProviderCapabilities;
import com.payments.gateway.provider.spi.ProviderCapabilities.CurrencySupport;
import com.payments.gateway.provider.spi.ProviderCapabilities.MethodSupport;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.VoidRequest;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;

class RoutingEngineTest {

    private final List<RoutingRule> rules = new ArrayList<>();
    private ProviderHealthTracker health;
    private ProviderClient client;
    private ProviderRegistry registry;
    private ProviderFeeService fees;
    private RoutingEngine engine;
    private final Map<String, ProviderFeeSchedule> feeSchedules = new HashMap<>();

    @BeforeEach
    void setUp() {
        StubProvider alpha = new StubProvider("ALPHA", Set.of(MethodType.UPI, MethodType.CARD, MethodType.NETBANKING));
        StubProvider beta = new StubProvider("BETA", Set.of(MethodType.UPI, MethodType.CARD));
        StubProvider gamma = new StubProvider("GAMMA", Set.of(MethodType.UPI));
        @SuppressWarnings("unchecked")
        ObjectProvider<PaymentProvider> beans = mock(ObjectProvider.class);
        when(beans.orderedStream()).thenAnswer(invocation -> Stream.of(alpha, beta, gamma));
        registry = new ProviderRegistry(beans);
        health = new ProviderHealthTracker();
        client = new ProviderClient(registry, health, new SimpleMeterRegistry(), new MerchantAccountResolver() {
            @Override
            public MerchantAccount require(String merchantId, String providerCode) {
                return new MerchantAccount("mpa_" + providerCode, merchantId, providerCode, Map.of());
            }

            @Override
            public Optional<MerchantAccount> findById(String accountId) {
                return Optional.empty();
            }
        });
        RoutingRuleRepository repository = mock(RoutingRuleRepository.class);
        when(repository.findAll()).thenAnswer(invocation -> List.copyOf(rules));
        RoutingRuleCache cache = new RoutingRuleCache(repository);
        fees = mock(ProviderFeeService.class);
        when(fees.effective(anyString(), anyCollection(), any(), anyString(), any())).thenAnswer(invocation -> Map.copyOf(feeSchedules));
        engine = new RoutingEngine(registry, client, health, cache, () -> 0.9, fees, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            RoutingEngine.betaSampler(new Random(1234)), RoutingEngine.betaSampler(new Random(5678)));
    }

    private static RoutingContext context(PaymentMethod method, long amount, Set<String> linked) {
        return new RoutingContext("mer_1", method, Money.of(amount, "INR"), CaptureMethod.AUTOMATIC, linked);
    }

    private static RoutingRule rule(String id, String merchantId, int priority, RoutingStrategy strategy,
                                    List<RuleCondition> conditions, boolean allowFallback, RoutingRule.Target... targets) {
        return new RoutingRule(id, merchantId, id, priority, true, conditions, strategy, List.of(targets), allowFallback, 0,
                Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void filtersByLinkedProvidersAndCapabilities() {
        RoutingDecision decision = engine.route(context(PaymentMethod.netbanking("HDFC"), 10_000, Set.of("ALPHA", "BETA")));

        assertThat(decision.providers()).containsExactly("ALPHA");
        assertThat(engine.route(context(PaymentMethod.netbanking("HDFC"), 10_000, Set.of("BETA"))).reason())
                .isEqualTo(RoutingDecision.Reason.NO_CAPABLE_PROVIDER);
    }

        @Test
        void foreignCurrenciesAreCardOnlyAndUseTheirOwnRangesAndSteps() {
        for (Money amount : List.of(Money.of(1, "JPY"), Money.of(99, "JPY"), Money.of(100, "KWD"),
            Money.of(3_000_000, "KWD"))) {
            assertThat(engine.route(new RoutingContext("mer_1", PaymentMethod.card(), amount,
                CaptureMethod.MANUAL, Set.of("ALPHA", "BETA"))).providers()).containsExactly("ALPHA");
            assertThat(engine.route(new RoutingContext("mer_1", PaymentMethod.upi(UpiFlow.INTENT, null), amount,
                CaptureMethod.AUTOMATIC, Set.of("ALPHA", "BETA"))).isEmpty()).isTrue();
        }
        for (Money amount : List.of(Money.of(100, "JPY"), Money.of(90, "KWD"), Money.of(101, "KWD"),
            Money.of(3_000_010, "KWD"), Money.of(100, "EUR"))) {
            assertThat(engine.route(new RoutingContext("mer_1", PaymentMethod.card(), amount,
                CaptureMethod.AUTOMATIC, Set.of("ALPHA", "BETA"))).reason())
                .isEqualTo(RoutingDecision.Reason.NO_CAPABLE_PROVIDER);
        }
        assertThat(engine.route(new RoutingContext("mer_1", PaymentMethod.card(), Money.of(10, "JPY"),
            CaptureMethod.AUTOMATIC, Set.of("BETA"))).isEmpty()).isTrue();
        }

    @Test
    void dynamicOrderingPrefersHealthierProvider() {
        for (int i = 0; i < 30; i++) {
            health.recordAttemptOutcome("ALPHA", MethodType.UPI, i % 2 == 0, FailureCategory.PROVIDER);
            health.recordAttemptOutcome("BETA", MethodType.UPI, true, null);
        }
        RoutingDecision decision = engine.route(context(PaymentMethod.upi(UpiFlow.INTENT, null), 10_000, Set.of("ALPHA", "BETA")));

        assertThat(decision.strategy()).isEqualTo(RoutingStrategy.DYNAMIC);
        assertThat(decision.providers()).containsExactly("BETA", "ALPHA");
    }

    @Test
    void customerCausedFailuresDoNotPenalizeProviders() {
        for (int i = 0; i < 30; i++) {
            health.recordAttemptOutcome("ALPHA", MethodType.UPI, false, FailureCategory.CUSTOMER);
        }
        assertThat(health.samples("ALPHA", MethodType.UPI)).isZero();
    }

    @Test
    void predictionUsesBankHistoryOnlyAfterEnoughObservationsWithoutPenalizingTechnicalHealth() {
        for (int index = 0; index < 19; index++) {
            health.recordAttemptOutcome("ALPHA", MethodType.NETBANKING, "HDFC", true, null);
        }
        for (int index = 0; index < 21; index++) {
            health.recordAttemptOutcome("ALPHA", MethodType.NETBANKING, "SBIN", false, FailureCategory.ISSUER);
        }
        assertThat(health.prediction("ALPHA", MethodType.NETBANKING, "HDFC"))
                .isEqualTo(new ProviderHealthTracker.Prediction(19, 21, false));
        health.recordAttemptOutcome("ALPHA", MethodType.NETBANKING, "HDFC", true, null);
        assertThat(health.prediction("ALPHA", MethodType.NETBANKING, "HDFC"))
                .isEqualTo(new ProviderHealthTracker.Prediction(20, 0, true));
        assertThat(health.prediction("ALPHA", MethodType.NETBANKING, "SBIN"))
                .isEqualTo(new ProviderHealthTracker.Prediction(0, 21, true));
        assertThat(health.prediction("ALPHA", MethodType.NETBANKING, "ICIC"))
                .isEqualTo(new ProviderHealthTracker.Prediction(20, 21, false));
        assertThat(health.samples("ALPHA", MethodType.NETBANKING)).isEqualTo(20);
        assertThat(health.successRate("ALPHA", MethodType.NETBANKING)).isEqualTo(1);
        assertThat(health.prediction("ALPHA", MethodType.CARD, null).mean()).isEqualTo(0.95);
    }

    @Test
    void predictionExcludesCustomerRiskAndValidationAndKeepsItsPrior() {
        for (FailureCategory excluded : List.of(FailureCategory.CUSTOMER, FailureCategory.RISK, FailureCategory.VALIDATION)) {
            health.recordAttemptOutcome("ALPHA", MethodType.CARD, false, excluded);
        }
        health.recordAttemptOutcome("ALPHA", MethodType.CARD, false, null);
        assertThat(health.prediction("ALPHA", MethodType.CARD, null)).isEqualTo(new ProviderHealthTracker.Prediction(0, 0, false));
        health.recordAttemptOutcome("ALPHA", MethodType.CARD, false, FailureCategory.NOT_SUBMITTED);
        assertThat(health.prediction("ALPHA", MethodType.CARD, null).beta()).isEqualTo(2);
        health.reset();
        assertThat(health.prediction("ALPHA", MethodType.CARD, null).mean()).isEqualTo(0.95);
    }

    @Test
    void predictionKeepsOnlyTheLastHundredQualifiedOutcomes() {
        for (int index = 0; index < 15; index++) {
            health.recordAttemptOutcome("ALPHA", MethodType.CARD, false, FailureCategory.PROVIDER);
        }
        for (int index = 0; index < 100; index++) {
            health.recordAttemptOutcome("ALPHA", MethodType.CARD, true, null);
        }
        assertThat(health.prediction("ALPHA", MethodType.CARD, null)).isEqualTo(new ProviderHealthTracker.Prediction(100, 0, false));
        health.recordAttemptOutcome("ALPHA", MethodType.CARD, false, FailureCategory.TIMEOUT);
        assertThat(health.prediction("ALPHA", MethodType.CARD, null)).isEqualTo(new ProviderHealthTracker.Prediction(99, 1, false));
    }

    @Test
    void merchantRuleWinsOverGlobalRuleAndFallbackIsAppended() {
        rules.add(rule("rr_global", null, 1, RoutingStrategy.PRIORITY, List.of(), true, new RoutingRule.Target("GAMMA", 1)));
        rules.add(rule("rr_merchant", "mer_1", 5, RoutingStrategy.PRIORITY,
                List.of(new RuleCondition("method", "eq", "upi"), new RuleCondition("amount", "gte", 5_000)), true,
                new RoutingRule.Target("BETA", 1)));

        RoutingDecision decision = engine.route(context(PaymentMethod.upi(UpiFlow.QR, null), 10_000, Set.of("ALPHA", "BETA", "GAMMA")));

        assertThat(decision.ruleId()).isEqualTo("rr_merchant");
        assertThat(decision.providers()).first().isEqualTo("BETA");
        assertThat(decision.providers()).containsExactlyInAnyOrder("BETA", "ALPHA", "GAMMA");
    }

    @Test
    void ruleWithoutFallbackRestrictsCandidates() {
        rules.add(rule("rr_strict", null, 1, RoutingStrategy.PRIORITY, List.of(new RuleCondition("method", "in", List.of("upi"))),
                false, new RoutingRule.Target("GAMMA", 1)));

        assertThat(engine.route(context(PaymentMethod.upi(UpiFlow.INTENT, null), 10_000, Set.of("ALPHA", "GAMMA"))).providers())
                .containsExactly("GAMMA");
    }

    @Test
    void costRanksFeesPerPredictedSuccessAndPlacesUnknownPricesLast() {
        rules.add(rule("rr_cost", null, 1, RoutingStrategy.COST, List.of(), false,
                new RoutingRule.Target("ALPHA", 1), new RoutingRule.Target("BETA", 1)));
        feeSchedules.put("ALPHA", price("ALPHA", 200));
        feeSchedules.put("BETA", price("BETA", 100));
        RoutingContext context = context(PaymentMethod.card(), 10_000, Set.of("ALPHA", "BETA"));
        assertThat(engine.route(context).providers()).containsExactly("BETA", "ALPHA");
        for (int index = 0; index < 100; index++) {
            health.recordAttemptOutcome("BETA", MethodType.CARD, false, FailureCategory.ISSUER);
        }
        assertThat(engine.route(context).providers()).containsExactly("ALPHA", "BETA");
        feeSchedules.remove("ALPHA");
        assertThat(engine.route(context).providers()).containsExactly("BETA", "ALPHA");
        feeSchedules.clear();
        assertThat(engine.route(context).live().reason()).isEqualTo("missing_fee_schedule");
        health.recordLatency("ALPHA", Duration.ofSeconds(5));
        assertThat(engine.route(context).providers()).containsExactly("BETA", "ALPHA");
    }

    @Test
    void adaptiveExploresOnlyEveryTenthLiveEvaluationAndPreviewsConsumeNothing() {
        rules.add(rule("rr_adaptive", null, 1, RoutingStrategy.ADAPTIVE, List.of(), false,
                new RoutingRule.Target("ALPHA", 1), new RoutingRule.Target("BETA", 1)));
        RoutingContext context = context(PaymentMethod.card(), 10_000, Set.of("ALPHA", "BETA"));
        for (int index = 1; index <= 30; index++) {
            for (int preview = 0; preview < 3; preview++) {
                assertThat(engine.preview(context).live().exploration()).isFalse();
            }
            RoutingDecision decision = engine.route(context);
            assertThat(decision.live().exploration()).as("evaluation %s", index).isEqualTo(index % 10 == 0);
            if (index % 10 == 0) {
                assertThat(decision.live().estimates()).allSatisfy(estimate -> assertThat(estimate.sampledSuccess()).isBetween(0.0, 1.0));
            }
        }
    }

    @Test
    void adaptiveExplorationBudgetIsAtomicAcrossConcurrentEvaluations() throws Exception {
        rules.add(rule("rr_concurrent", null, 1, RoutingStrategy.ADAPTIVE, List.of(), false,
                new RoutingRule.Target("ALPHA", 1), new RoutingRule.Target("BETA", 1)));
        RoutingContext context = context(PaymentMethod.card(), 10_000, Set.of("ALPHA", "BETA"));
        int explored = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int batch = 1; batch <= 20; batch++) {
                List<Future<RoutingDecision>> results = new ArrayList<>();
                for (int index = 0; index < 37; index++) {
                    results.add(executor.submit(() -> engine.route(context)));
                }
                for (Future<RoutingDecision> result : results) {
                    if (result.get(5, TimeUnit.SECONDS).live().exploration()) {
                        explored++;
                    }
                }
                assertThat(explored).as("completed batch %s", batch).isEqualTo(batch * 37 / 10);
            }
        }
    }

    @Test
    void adaptiveLearnsTheBetterProviderAndAdaptsWhenSuccessRatesChange() {
        rules.add(rule("rr_learning", null, 1, RoutingStrategy.ADAPTIVE, List.of(), false,
                new RoutingRule.Target("ALPHA", 1), new RoutingRule.Target("BETA", 1)));
        RoutingContext context = context(PaymentMethod.card(), 10_000, Set.of("ALPHA", "BETA"));
        Random outcomes = new Random(2847);
        for (int phase = 0; phase < 2; phase++) {
            String better = phase == 0 ? "BETA" : "ALPHA";
            int betterInLastThousand = 0;
            int explored = 0;
            for (int index = 0; index < 4000; index++) {
                RoutingDecision decision = engine.route(context);
                String chosen = decision.providers().getFirst();
                boolean success = outcomes.nextDouble() < (chosen.equals(better) ? 0.97 : 0.55);
                health.recordAttemptOutcome(chosen, MethodType.CARD, success, success ? null : FailureCategory.ISSUER);
                if (decision.live().exploration()) {
                    explored++;
                }
                if (index >= 3000 && chosen.equals(better)) {
                    betterInLastThousand++;
                }
            }
            assertThat(explored).isEqualTo(400);
            assertThat(betterInLastThousand).as("phase %s should favor %s", phase, better).isGreaterThanOrEqualTo(900);
        }
    }

    @Test
    void shadowSamplingAndPreviewsDoNotChangeLiveChoicesOrRandomDraws() {
        RoutingRule liveRule = rule("rr_isolated", null, 1, RoutingStrategy.ADAPTIVE, List.of(), false,
                new RoutingRule.Target("ALPHA", 1), new RoutingRule.Target("BETA", 1));
        RoutingRule shadowRule = new RoutingRule(liveRule.id(), null, liveRule.name(), 1, true, List.of(),
                RoutingStrategy.ADAPTIVE, liveRule.targets(), false, 0, Instant.EPOCH, Instant.EPOCH, RoutingStrategy.ADAPTIVE);
        rules.add(shadowRule);
        RoutingRuleRepository liveRepository = mock(RoutingRuleRepository.class);
        when(liveRepository.findAll()).thenReturn(List.of(liveRule));
        RoutingEngine liveOnly = new RoutingEngine(registry, client, health, new RoutingRuleCache(liveRepository),
                () -> 0.9, fees, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                RoutingEngine.betaSampler(new Random(1234)), (alpha, beta) -> {
                    throw new AssertionError("No shadow evaluation was configured");
                });
        RoutingContext context = context(PaymentMethod.card(), 10_000, Set.of("ALPHA", "BETA"));
        for (int index = 0; index < 250; index++) {
            engine.preview(context);
            engine.preview(context);
            RoutingDecision expected = liveOnly.route(context);
            RoutingDecision actual = engine.route(context);
            assertThat(actual.live()).isEqualTo(expected.live());
            assertThat(actual.providers()).isEqualTo(expected.providers());
            assertThat(actual.shadow().exploration()).isEqualTo((index + 1) % 10 == 0);
        }
    }

    @Test
    void weightedPreviewsDoNotConsumeTheLiveRandomStream() {
        RoutingRuleRepository repository = mock(RoutingRuleRepository.class);
        when(repository.findAll()).thenReturn(List.of(rule("rr_weighted_preview", null, 1, RoutingStrategy.WEIGHTED,
                List.of(), false, new RoutingRule.Target("ALPHA", 80), new RoutingRule.Target("BETA", 20))));
        AtomicInteger draws = new AtomicInteger();
        RoutingEngine previewed = new RoutingEngine(registry, client, health, new RoutingRuleCache(repository),
                () -> { draws.incrementAndGet(); return 0.9; }, fees, Clock.systemUTC(),
                RoutingEngine.betaSampler(new Random(1)), RoutingEngine.betaSampler(new Random(2)));
        RoutingContext context = context(PaymentMethod.card(), 10_000, Set.of("ALPHA", "BETA"));
        for (int index = 0; index < 25; index++) {
            assertThat(previewed.preview(context).providers()).containsExactly("ALPHA", "BETA");
        }
        assertThat(draws).hasValue(0);
        assertThat(previewed.route(context).providers()).containsExactly("BETA", "ALPHA");
        assertThat(draws).hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(value = RoutingStrategy.class, names = {"COST", "ADAPTIVE"})
    void advancedStrategiesNeverSelectAnOpenCircuitEvenInShadowOrExploration(RoutingStrategy strategy) {
        for (int index = 0; index < 12; index++) {
            assertThatThrownBy(() -> client.initiate("mer_1", "GAMMA", null)).isInstanceOf(ProviderUnavailableException.class);
        }
        for (int index = 0; index < 100; index++) {
            health.recordAttemptOutcome("GAMMA", MethodType.UPI, true, null);
        }
        feeSchedules.put("GAMMA", new ProviderFeeSchedule("pfs_gamma", "mer_1", "GAMMA", MethodType.UPI, "INR", 0,
                0, 0, ProviderFeeSchedule.Collection.WITHHELD, Instant.EPOCH, Instant.EPOCH));
        feeSchedules.put("ALPHA", new ProviderFeeSchedule("pfs_alpha", "mer_1", "ALPHA", MethodType.UPI, "INR", 100,
                0, 0, ProviderFeeSchedule.Collection.WITHHELD, Instant.EPOCH, Instant.EPOCH));
        rules.add(new RoutingRule("rr_circuit", null, "Circuit guard", 1, true, List.of(), strategy,
                List.of(new RoutingRule.Target("GAMMA", 1), new RoutingRule.Target("ALPHA", 1)), false, 0,
                Instant.EPOCH, Instant.EPOCH, RoutingStrategy.ADAPTIVE));
        RoutingContext context = context(PaymentMethod.upi(UpiFlow.INTENT, null), 10_000, Set.of("ALPHA", "GAMMA"));
        for (int index = 0; index < 100; index++) {
            RoutingDecision decision = engine.route(context);
            assertThat(decision.providers()).containsExactly("ALPHA");
            assertThat(decision.shadow().providers()).containsExactly("ALPHA");
        }
    }

    @Test
    void routingUsesTheKnownBanksPosteriorInsteadOfTheMethodAverage() {
        for (int index = 0; index < 30; index++) {
            health.recordAttemptOutcome("ALPHA", MethodType.NETBANKING, "HDFC", false, FailureCategory.ISSUER);
        }
        for (int index = 0; index < 70; index++) {
            health.recordAttemptOutcome("ALPHA", MethodType.NETBANKING, "SBIN", true, null);
        }
        rules.add(rule("rr_bank", null, 1, RoutingStrategy.ADAPTIVE, List.of(), false, new RoutingRule.Target("ALPHA", 1)));
        RoutingDecision decision = engine.preview(context(PaymentMethod.netbanking("HDFC"), 10_000, Set.of("ALPHA")));
        assertThat(decision.live().estimates()).singleElement().satisfies(estimate -> {
            assertThat(estimate.bankSpecific()).isTrue();
            assertThat(estimate.samples()).isEqualTo(30);
            assertThat(estimate.predictedSuccess()).isEqualTo(19.0 / 50);
        });
    }

    @Test
    void explorationOrdersByTheSampleOnlyWithinItsBudgetedSlot() {
        for (int index = 0; index < 100; index++) {
            health.recordAttemptOutcome("ALPHA", MethodType.CARD, true, null);
        }
        RoutingRuleRepository repository = mock(RoutingRuleRepository.class);
        when(repository.findAll()).thenReturn(List.of(rule("rr_forced_sample", null, 1, RoutingStrategy.ADAPTIVE,
                List.of(), false, new RoutingRule.Target("ALPHA", 1), new RoutingRule.Target("BETA", 1))));
        AtomicInteger draws = new AtomicInteger();
        RoutingEngine sampled = new RoutingEngine(registry, client, health, new RoutingRuleCache(repository),
                () -> 0.9, fees, Clock.systemUTC(), (alpha, beta) -> {
                    draws.incrementAndGet();
                    return alpha > 100 ? 0.1 : 0.99;
                }, (alpha, beta) -> { throw new AssertionError("No shadow configured"); });
        RoutingContext context = context(PaymentMethod.card(), 10_000, Set.of("ALPHA", "BETA"));
        for (int index = 0; index < 9; index++) {
            assertThat(sampled.route(context).providers()).containsExactly("ALPHA", "BETA");
        }
        assertThat(draws).hasValue(0);
        assertThat(sampled.route(context).providers()).containsExactly("BETA", "ALPHA");
        assertThat(draws).hasValue(2);
        assertThat(sampled.route(context).providers()).containsExactly("ALPHA", "BETA");
    }

    @Test
    void productionSamplerProducesVariableBetaSamplesAroundThePosteriorMean() {
        for (double[] shapes : new double[][] {{19, 1}, {2, 8}}) {
            var sampler = RoutingEngine.betaSampler(new Random(9417));
            var samples = java.util.stream.IntStream.range(0, 4000)
                    .mapToDouble(index -> sampler.applyAsDouble(shapes[0], shapes[1])).summaryStatistics();
            double expected = shapes[0] / (shapes[0] + shapes[1]);
            assertThat(samples.getAverage()).isBetween(expected - 0.01, expected + 0.01);
            assertThat(samples.getMin()).isBetween(0.0, expected - 0.05);
            assertThat(samples.getMax()).isBetween(expected + 0.02, 1.0);
        }
    }

    private static ProviderFeeSchedule price(String provider, long fixed) {
        return new ProviderFeeSchedule("pfs_" + provider, "mer_1", provider, MethodType.CARD, "INR", fixed,
                0, 0, ProviderFeeSchedule.Collection.WITHHELD, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void weightedStrategyPicksByWeight() {
        rules.add(rule("rr_weighted", null, 1, RoutingStrategy.WEIGHTED, List.of(), false,
                new RoutingRule.Target("ALPHA", 80), new RoutingRule.Target("BETA", 20)));

        // random = 0.9 → pick lands in BETA's 20% slice (80..100)
        assertThat(engine.route(context(PaymentMethod.card(), 10_000, Set.of("ALPHA", "BETA"))).providers())
                .containsExactly("BETA", "ALPHA");
    }

    @Test
    void openCircuitsAreExcluded() {
        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> client.initiate("mer_1", "GAMMA", null)).isInstanceOf(ProviderUnavailableException.class);
        }
        assertThat(client.isAvailable("GAMMA")).isFalse();
        assertThat(engine.route(context(PaymentMethod.upi(UpiFlow.INTENT, null), 10_000, Set.of("GAMMA"))).reason())
                .isEqualTo(RoutingDecision.Reason.ALL_UNAVAILABLE);
    }

    @Test
    void conditionsValidateAndMatch() {
        assertThat(new RuleCondition("amount", "gte", "abc").validate()).isPresent();
        assertThat(new RuleCondition("colour", "eq", "red").validate()).isPresent();
        assertThat(new RuleCondition("method", "in", "upi").validate()).isPresent();
        assertThat(new RuleCondition("upi_flow", "eq", "intent").matches(context(PaymentMethod.upi(UpiFlow.INTENT, null), 100, Set.of())))
                .isTrue();
        assertThat(new RuleCondition("bank_code", "not_in", List.of("SBIN")).matches(context(PaymentMethod.netbanking("HDFC"), 100, Set.of())))
                .isTrue();
    }

    /** Provider stub: GAMMA is always unreachable to exercise the circuit breaker. */
    private record StubProvider(String code, Set<MethodType> methods) implements PaymentProvider {

        @Override
        public ProviderCapabilities capabilities() {
            Map<MethodType, MethodSupport> support = new java.util.EnumMap<>(MethodType.class);
            methods.forEach(m -> support.put(m, new MethodSupport(Set.of(UpiFlow.values()), 100, 10_000_000, true)));
                ProviderCapabilities capabilities = new ProviderCapabilities(support, Set.of("INR"), true, true, false);
                return "ALPHA".equals(code) ? capabilities.withForeignCurrencies(Map.of(
                    "JPY", new CurrencySupport(1, 99, 1), "KWD", new CurrencySupport(100, 3_000_000, 10))) : capabilities;
        }

        @Override
        public ProviderPaymentResult initiatePayment(MerchantAccount account, InitiatePaymentRequest request) {
            throw new ProviderUnavailableException(code, "unreachable");
        }

        @Override
        public ProviderPaymentResult fetchPaymentStatus(MerchantAccount account, PaymentStatusQuery query) {
            return ProviderPaymentResult.notFound();
        }

        @Override
        public ProviderPaymentResult capture(MerchantAccount account, CaptureRequest request) {
            return ProviderPaymentResult.notFound();
        }

        @Override
        public ProviderPaymentResult voidAuthorization(MerchantAccount account, VoidRequest request) {
            return ProviderPaymentResult.notFound();
        }

        @Override
        public ProviderRefundResult refund(MerchantAccount account, RefundRequest request) {
            return ProviderRefundResult.notFound();
        }

        @Override
        public ProviderRefundResult fetchRefundStatus(MerchantAccount account, RefundStatusQuery query) {
            return ProviderRefundResult.notFound();
        }

        @Override
        public List<ProviderEvent> parseWebhook(MerchantAccount account, InboundWebhook webhook) {
            return List.of();
        }
    }
}
