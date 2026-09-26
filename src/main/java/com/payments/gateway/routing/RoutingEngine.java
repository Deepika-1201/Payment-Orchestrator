package com.payments.gateway.routing;

import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderHealthTracker;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.shared.model.MethodType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
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

    @Autowired
    public RoutingEngine(ProviderRegistry registry, ProviderClient providerClient, ProviderHealthTracker health,
                         RoutingRuleCache rules) {
        this(registry, providerClient, health, rules, () -> ThreadLocalRandom.current().nextDouble());
    }

    RoutingEngine(ProviderRegistry registry, ProviderClient providerClient, ProviderHealthTracker health,
                  RoutingRuleCache rules, DoubleSupplier random) {
        this.registry = registry;
        this.providerClient = providerClient;
        this.health = health;
        this.rules = rules;
        this.random = random;
    }

    public RoutingDecision route(RoutingContext context) {
        List<String> capable = registry.all().stream()
                .filter(provider -> context.linkedProviders().contains(provider.code()))
                .filter(provider -> provider.capabilities().supports(context.method(), context.amount(), context.captureMethod()))
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
            List<String> ordered = new ArrayList<>(switch (rule.strategy()) {
                case PRIORITY -> targets.stream().map(RoutingRule.Target::provider).toList();
                case WEIGHTED -> weighted(targets);
                case DYNAMIC -> byScore(targets.stream().map(RoutingRule.Target::provider).toList(), method);
            });
            if (rule.allowFallback()) {
                byScore(available, method).stream().filter(provider -> !ordered.contains(provider)).forEach(ordered::add);
            }
            return new RoutingDecision(ordered, rule.id(), rule.strategy(), RoutingDecision.Reason.ROUTED);
        }
        return new RoutingDecision(byScore(available, method), null, RoutingStrategy.DYNAMIC, RoutingDecision.Reason.ROUTED);
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
