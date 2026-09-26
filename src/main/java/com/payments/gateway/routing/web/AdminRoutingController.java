package com.payments.gateway.routing.web;

import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderHealthTracker;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.routing.RoutingRule;
import com.payments.gateway.routing.RoutingRuleCache;
import com.payments.gateway.routing.RoutingRuleRepository;
import com.payments.gateway.routing.RoutingStrategy;
import com.payments.gateway.routing.RuleCondition;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1")
public class AdminRoutingController {

    public record RuleRequest(
            String merchantId,
            @NotBlank @Size(max = 200) String name,
            @NotNull @Min(0) @Max(100_000) Integer priority,
            Boolean enabled,
            @Size(max = 20) List<@Valid ConditionRequest> conditions,
            @NotBlank String strategy,
            @NotEmpty @Size(max = 10) List<@Valid TargetRequest> targets,
            Boolean allowFallback,
            Long version) {
    }

    public record ConditionRequest(@NotBlank String field, @NotBlank String op, @NotNull Object value) {
    }

    public record TargetRequest(@NotBlank String provider, @Min(0) @Max(10_000) Integer weight) {
    }

    private final RoutingRuleRepository repository;
    private final RoutingRuleCache cache;
    private final ProviderRegistry registry;
    private final ProviderClient providerClient;
    private final ProviderHealthTracker health;
    private final AuditLogger audit;
    private final Clock clock;

    public AdminRoutingController(RoutingRuleRepository repository, RoutingRuleCache cache, ProviderRegistry registry,
                                  ProviderClient providerClient, ProviderHealthTracker health, AuditLogger audit, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.registry = registry;
        this.providerClient = providerClient;
        this.health = health;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/routing-rules")
    public Map<String, Object> list() {
        return Map.of("data", repository.findAll());
    }

    @PostMapping("/routing-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutingRule create(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                              @Valid @RequestBody RuleRequest request) {
        Instant now = clock.instant();
        RoutingRule rule = toRule(Ids.newId("rr"), request, 0, now, now);
        repository.insert(rule);
        audit.record("ADMIN", actor, "routing_rule.created", "routing_rule", rule.id(), Map.of("name", rule.name()));
        cache.refresh();
        return repository.findById(rule.id()).orElseThrow();
    }

    @PutMapping("/routing-rules/{id}")
    public RoutingRule update(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor, @PathVariable String id,
                              @Valid @RequestBody RuleRequest request) {
        RoutingRule existing = repository.findById(id).orElseThrow(() -> GatewayException.notFound("Routing rule", id));
        if (request.version() == null) {
            throw GatewayException.validation("version", "is required for updates");
        }
        RoutingRule rule = toRule(id, request, request.version(), existing.createdAt(), clock.instant());
        if (!repository.update(rule)) {
            throw new GatewayException(ErrorCode.PAYMENT_INVALID_STATE, "Routing rule was modified concurrently; reload and retry");
        }
        audit.record("ADMIN", actor, "routing_rule.updated", "routing_rule", id, Map.of("version", request.version()));
        cache.refresh();
        return repository.findById(id).orElseThrow();
    }

    @GetMapping("/providers/health")
    public Map<String, Object> providerHealth() {
        List<Map<String, Object>> providers = registry.all().stream().map(provider -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("provider", provider.code());
            entry.put("circuit_state", providerClient.circuitState(provider.code()).toLowerCase(java.util.Locale.ROOT));
            entry.put("latency_ms", Math.round(health.latencyMillis(provider.code())));
            entry.put("methods", methodHealth(provider));
            return entry;
        }).toList();
        return Map.of("data", providers);
    }

    private List<Map<String, Object>> methodHealth(PaymentProvider provider) {
        return provider.capabilities().methods().keySet().stream().sorted().map(method -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("method", WireEnums.wire(method));
            entry.put("success_rate", health.successRate(provider.code(), method));
            entry.put("samples", health.samples(provider.code(), method));
            entry.put("score", health.score(provider.code(), method));
            return entry;
        }).toList();
    }

    private RoutingRule toRule(String id, RuleRequest request, long version, Instant createdAt, Instant updatedAt) {
        RoutingStrategy strategy = WireEnums.parse(RoutingStrategy.class, request.strategy(), "strategy");
        List<RuleCondition> conditions = request.conditions() == null ? List.of() : request.conditions().stream()
                .map(c -> new RuleCondition(c.field(), c.op(), c.value()))
                .toList();
        for (int i = 0; i < conditions.size(); i++) {
            int index = i;
            conditions.get(i).validate().ifPresent(problem -> {
                throw GatewayException.validation("conditions[" + index + "]", problem);
            });
        }
        List<RoutingRule.Target> targets = request.targets().stream()
                .map(t -> new RoutingRule.Target(t.provider(), t.weight() == null ? 1 : t.weight()))
                .toList();
        for (RoutingRule.Target target : targets) {
            if (!registry.exists(target.provider())) {
                throw GatewayException.validation("targets", "contains unknown provider " + target.provider());
            }
        }
        return new RoutingRule(id, request.merchantId(), request.name(), request.priority(),
                request.enabled() == null || request.enabled(), conditions, strategy, targets,
                request.allowFallback() == null || request.allowFallback(), version, createdAt, updatedAt);
    }
}
