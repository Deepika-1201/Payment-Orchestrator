package com.payments.gateway.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Per-instance snapshot of enabled rules, refreshed periodically and on admin writes. */
@Component
public class RoutingRuleCache {

    private static final Logger log = LoggerFactory.getLogger(RoutingRuleCache.class);
    private static final Comparator<RoutingRule> ORDER = Comparator.comparingInt(RoutingRule::priority).thenComparing(RoutingRule::id);

    private record Snapshot(Map<String, List<RoutingRule>> byMerchant, List<RoutingRule> global) {
    }

    private final RoutingRuleRepository repository;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), List.of());
    private volatile boolean loaded;

    public RoutingRuleCache(RoutingRuleRepository repository) {
        this.repository = repository;
    }

    /** Merchant-specific rules first, then global rules, each ordered by priority. */
    public List<RoutingRule> rulesFor(String merchantId) {
        if (!loaded) {
            refresh();
        }
        Snapshot current = snapshot;
        List<RoutingRule> rules = new ArrayList<>(current.byMerchant().getOrDefault(merchantId, List.of()));
        rules.addAll(current.global());
        return rules;
    }

    @Scheduled(fixedDelay = 30, initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void refresh() {
        try {
            List<RoutingRule> enabled = repository.findAll().stream().filter(RoutingRule::enabled).toList();
            Map<String, List<RoutingRule>> byMerchant = enabled.stream()
                    .filter(rule -> rule.merchantId() != null)
                    .collect(Collectors.groupingBy(RoutingRule::merchantId,
                            Collectors.collectingAndThen(Collectors.toList(), list -> list.stream().sorted(ORDER).toList())));
            List<RoutingRule> global = enabled.stream().filter(rule -> rule.merchantId() == null).sorted(ORDER).toList();
            snapshot = new Snapshot(byMerchant, global);
            loaded = true;
        } catch (RuntimeException e) {
            log.warn("Routing rule refresh failed; keeping previous snapshot", e);
        }
    }
}
