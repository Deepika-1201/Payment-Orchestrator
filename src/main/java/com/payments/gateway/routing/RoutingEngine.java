package com.payments.gateway.routing;

import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderHealthTracker;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.routing.RoutingDecision.Estimate;
import com.payments.gateway.routing.RoutingDecision.Evaluation;
import com.payments.gateway.shared.model.MethodType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleSupplier;
import org.apache.commons.statistics.distribution.BetaDistribution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Selects an ordered list of providers: capability filter → circuit filter → first matching rule (merchant rules,
 * then global) → strategy; falls back to health-score ordering when no rule matches (ADR-009).
 */
@Component
public class RoutingEngine {

    private final ProviderRegistry registry;
    private final ProviderClient providerClient;
    private final ProviderHealthTracker health;
    private final RoutingRuleCache rules;
    private final DoubleSupplier random;
    private final ProviderFeeService fees;
    private final Clock clock;
    private final DoubleBinaryOperator liveSampler;
    private final DoubleBinaryOperator shadowSampler;
    private final Map<String, AtomicInteger> liveSlots = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> shadowSlots = new ConcurrentHashMap<>();

    @Autowired
    public RoutingEngine(ProviderRegistry registry, ProviderClient providerClient, ProviderHealthTracker health,
                                                 RoutingRuleCache rules, ProviderFeeService fees, Clock clock) {
                this(registry, providerClient, health, rules, () -> ThreadLocalRandom.current().nextDouble(), fees, clock,
                                betaSampler(new Random()), betaSampler(new Random()));
    }

    RoutingEngine(ProviderRegistry registry, ProviderClient providerClient, ProviderHealthTracker health,
                                    RoutingRuleCache rules, DoubleSupplier random, ProviderFeeService fees, Clock clock,
                                    DoubleBinaryOperator liveSampler, DoubleBinaryOperator shadowSampler) {
        this.registry = registry;
        this.providerClient = providerClient;
        this.health = health;
        this.rules = rules;
        this.random = random;
        this.fees = fees;
        this.clock = clock;
        this.liveSampler = liveSampler;
        this.shadowSampler = shadowSampler;
    }

    public RoutingDecision route(RoutingContext context) {
        return evaluate(context, false);
    }

    public RoutingDecision preview(RoutingContext context) {
        return evaluate(context, true);
    }

    private RoutingDecision evaluate(RoutingContext context, boolean preview) {
        List<String> capable = registry.all().stream()
                .filter(provider -> context.linkedProviders().contains(provider.code()))
                .filter(provider -> provider.capabilities().supports(context.method(), context.amount(),
                        context.captureMethod(), context.customerPhoneKnown()))
                .map(PaymentProvider::code)
                .toList();
        if (capable.isEmpty()) {
            return RoutingDecision.none(RoutingDecision.Reason.NO_CAPABLE_PROVIDER);
        }
        List<String> available = capable.stream().filter(providerClient::isAvailable).toList();
        if (available.isEmpty()) {
            return RoutingDecision.none(RoutingDecision.Reason.ALL_UNAVAILABLE);
        }
        MethodType method = context.method().type();
        for (RoutingRule rule : rules.rulesFor(context.merchantId())) {
            if (!rule.matches(context)) {
                continue;
            }
            List<RoutingRule.Target> targets = rule.targets().stream()
                    .filter(target -> available.contains(target.provider()))
                    .toList();
            if (targets.isEmpty()) {
                continue;
            }
            Instant at = clock.instant();
            Evaluation live = rank(rule.strategy(), targets, context, rule.id(), preview, false, at);
            Evaluation shadow = rule.shadowStrategy() == null ? null
                    : rank(rule.shadowStrategy(), targets, context, rule.id(), preview, true, at);
            live = fallback(live, rule.allowFallback(), available, method);
            if (shadow != null) {
                shadow = fallback(shadow, rule.allowFallback(), available, method);
            }
            return new RoutingDecision(live.providers(), rule.id(), rule.strategy(), RoutingDecision.Reason.ROUTED, live, shadow);
        }
        return new RoutingDecision(byScore(available, method), null, RoutingStrategy.DYNAMIC, RoutingDecision.Reason.ROUTED);
    }

    private Evaluation rank(RoutingStrategy strategy, List<RoutingRule.Target> targets, RoutingContext context,
                            String ruleId, boolean preview, boolean shadow, Instant at) {
        List<String> codes = targets.stream().map(RoutingRule.Target::provider).distinct().toList();
        if (strategy != RoutingStrategy.COST && strategy != RoutingStrategy.ADAPTIVE) {
            List<String> ordered = switch (strategy) {
                case PRIORITY -> codes;
                case WEIGHTED -> preview ? targets.stream().sorted(Comparator.comparingInt(RoutingRule.Target::weight).reversed())
                        .map(RoutingRule.Target::provider).toList() : weighted(targets);
                default -> byScore(codes, context.method().type());
            };
            return new Evaluation(strategy, ordered, strategy.name().toLowerCase(java.util.Locale.ROOT), false, List.of());
        }
        Map<String, ProviderFeeSchedule> schedules = strategy == RoutingStrategy.COST
                ? fees.effective(context.merchantId(), codes, context.method().type(), context.amount().currency(), at) : Map.of();
        boolean explore = strategy == RoutingStrategy.ADAPTIVE && !preview
                && (shadow ? shadowSlots : liveSlots).computeIfAbsent(ruleId, ignored -> new AtomicInteger())
                        .updateAndGet(value -> (value + 1) % 10) == 0;
        List<Estimate> estimates = new ArrayList<>();
        for (String code : codes) {
            ProviderHealthTracker.Prediction prediction = health.prediction(code, context.method().type(), context.method().bankCode());
            ProviderFeeSchedule schedule = schedules.get(code);
            Long fee = schedule == null ? null : schedule.estimate(context.amount()).amount();
            BigDecimal cost = fee == null ? null : BigDecimal.valueOf(fee)
                    .multiply(BigDecimal.valueOf(20L + prediction.samples()))
                    .divide(BigDecimal.valueOf(19L + prediction.successes()), MathContext.DECIMAL128);
            Double sample = explore ? (shadow ? shadowSampler : liveSampler).applyAsDouble(prediction.alpha(), prediction.beta()) : null;
            estimates.add(new Estimate(code, prediction.mean(), prediction.samples(), prediction.bankSpecific(),
                    schedule == null ? null : schedule.id(), fee, context.amount().currency(), cost, sample));
        }
        if (strategy == RoutingStrategy.COST && schedules.isEmpty()) {
            return new Evaluation(strategy, byScore(codes, context.method().type()), "missing_fee_schedule", false, estimates);
        }
        Comparator<Estimate> comparator = strategy == RoutingStrategy.COST
                ? Comparator.comparing(Estimate::costPerSuccess, Comparator.nullsLast(Comparator.naturalOrder()))
                : Comparator.comparingDouble((Estimate estimate) -> explore ? estimate.sampledSuccess() : estimate.predictedSuccess()).reversed();
        List<String> ordered = estimates.stream().sorted(comparator.thenComparing(Estimate::provider)).map(Estimate::provider).toList();
        return new Evaluation(strategy, ordered, strategy == RoutingStrategy.COST ? "cost_per_success"
                : explore ? "thompson_sample" : "posterior_mean", explore, estimates);
    }

    private Evaluation fallback(Evaluation evaluation, boolean enabled, List<String> available, MethodType method) {
        if (!enabled) {
            return evaluation;
        }
        List<String> ordered = new ArrayList<>(evaluation.providers());
        byScore(available, method).stream().filter(provider -> !ordered.contains(provider)).forEach(ordered::add);
        return new Evaluation(evaluation.strategy(), ordered, evaluation.reason(), evaluation.exploration(), evaluation.estimates());
    }

    static DoubleBinaryOperator betaSampler(Random random) {
        return (alpha, beta) -> BetaDistribution.of(alpha, beta).createSampler(random::nextLong).sample();
    }

    private List<String> byScore(List<String> providers, MethodType method) {
        return providers.stream()
                .sorted(Comparator.comparingDouble((String provider) -> health.score(provider, method)).reversed())
                .toList();
    }

    private List<String> weighted(List<RoutingRule.Target> targets) {
        int total = targets.stream().mapToInt(target -> Math.max(0, target.weight())).sum();
        List<RoutingRule.Target> byWeight = targets.stream()
                .sorted(Comparator.comparingInt(RoutingRule.Target::weight).reversed())
                .toList();
        if (total == 0) {
            return targets.stream().map(RoutingRule.Target::provider).toList();
        }
        double pick = random.getAsDouble() * total;
        RoutingRule.Target first = byWeight.getLast();
        double cumulative = 0;
        for (RoutingRule.Target target : targets) {
            cumulative += Math.max(0, target.weight());
            if (pick < cumulative) {
                first = target;
                break;
            }
        }
        List<String> ordered = new ArrayList<>();
        ordered.add(first.provider());
        for (RoutingRule.Target target : byWeight) {
            if (!ordered.contains(target.provider())) {
                ordered.add(target.provider());
            }
        }
        return ordered;
    }
}
