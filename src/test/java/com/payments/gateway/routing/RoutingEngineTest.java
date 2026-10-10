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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingEngineTest {

    private final List<RoutingRule> rules = new ArrayList<>();
    private ProviderHealthTracker health;
    private ProviderClient client;
    private RoutingEngine engine;

    @BeforeEach
    void setUp() {
        StubProvider alpha = new StubProvider("ALPHA", Set.of(MethodType.UPI, MethodType.CARD, MethodType.NETBANKING));
        StubProvider beta = new StubProvider("BETA", Set.of(MethodType.UPI, MethodType.CARD));
        StubProvider gamma = new StubProvider("GAMMA", Set.of(MethodType.UPI));
        @SuppressWarnings("unchecked")
        ObjectProvider<PaymentProvider> beans = mock(ObjectProvider.class);
        when(beans.orderedStream()).thenAnswer(invocation -> Stream.of(alpha, beta, gamma));
        ProviderRegistry registry = new ProviderRegistry(beans);
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
        engine = new RoutingEngine(registry, client, health, cache, () -> 0.9);
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
