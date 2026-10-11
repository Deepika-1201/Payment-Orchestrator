package com.payments.gateway.routing.web;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderHealthTracker;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.routing.RoutingRule;
import com.payments.gateway.routing.RoutingContext;
import com.payments.gateway.routing.RoutingDecision;
import com.payments.gateway.routing.RoutingDecisionRepository;
import com.payments.gateway.routing.RoutingEngine;
import com.payments.gateway.routing.ProviderFeeSchedule;
import com.payments.gateway.routing.ProviderFeeService;
import com.payments.gateway.routing.RoutingRuleCache;
import com.payments.gateway.routing.RoutingRuleRepository;
import com.payments.gateway.routing.RoutingStrategy;
import com.payments.gateway.routing.RuleCondition;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
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
            Long version,
            String shadowStrategy) {
    }

    public record ConditionRequest(@NotBlank String field, @NotBlank String op, @NotNull Object value) {
    }

    public record TargetRequest(@NotBlank String provider, @Min(0) @Max(10_000) Integer weight) {
    }

    public record FeeScheduleRequest(@NotBlank String method, @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                                     @NotNull @Min(0) @Max(1_000_000_000_000L) Long fixedAmount,
                                     @Min(0) @Max(10_000) Integer rateBps, @Min(0) @Max(10_000) Integer taxBps,
                                     String collection, Instant effectiveAt) {
    }

    public record PreviewRequest(@NotBlank String merchantId, @NotBlank String method,
                                  @NotNull @Min(1) @Max(1_000_000_000_000L) Long amount,
                                  @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                                  String upiFlow, String bankCode, String methodProvider,
                                  String captureMethod, Boolean customerPhoneKnown) {
    }

    private final RoutingRuleRepository repository;
    private final RoutingRuleCache cache;
    private final ProviderRegistry registry;
    private final ProviderClient providerClient;
    private final ProviderHealthTracker health;
    private final AuditLogger audit;
    private final Clock clock;
    private final ProviderFeeService fees;
    private final RoutingEngine routing;
    private final RoutingDecisionRepository decisions;
    private final MerchantDirectory merchants;

    public AdminRoutingController(RoutingRuleRepository repository, RoutingRuleCache cache, ProviderRegistry registry,
                                  ProviderClient providerClient, ProviderHealthTracker health, AuditLogger audit, Clock clock,
                                  ProviderFeeService fees, RoutingEngine routing, RoutingDecisionRepository decisions,
                                  MerchantDirectory merchants) {
        this.repository = repository;
        this.cache = cache;
        this.registry = registry;
        this.providerClient = providerClient;
        this.health = health;
        this.audit = audit;
        this.clock = clock;
        this.fees = fees;
        this.routing = routing;
        this.decisions = decisions;
        this.merchants = merchants;
    }

    @PostMapping("/merchants/{id}/provider-accounts/{provider}/fee-schedules")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderFeeSchedule publishFee(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                           @PathVariable String id, @PathVariable String provider,
                                           @Valid @RequestBody FeeScheduleRequest request) {
        return fees.publish(id, provider, new ProviderFeeService.PublishCommand(
                WireEnums.parse(MethodType.class, request.method(), "method"), request.currency(), request.fixedAmount(),
                request.rateBps() == null ? 0 : request.rateBps(), request.taxBps() == null ? 1800 : request.taxBps(),
                request.collection() == null ? ProviderFeeSchedule.Collection.WITHHELD
                        : WireEnums.parse(ProviderFeeSchedule.Collection.class, request.collection(), "collection"),
                request.effectiveAt()), actor);
    }

    @GetMapping("/merchants/{id}/provider-accounts/{provider}/fee-schedules")
    public Map<String, Object> feeHistory(@PathVariable String id, @PathVariable String provider) {
        return Map.of("data", fees.history(id, provider));
    }

    @PostMapping("/routing/preview")
    @RequiresAdmin(AdminPermission.READ)
    public RoutingDecision preview(@Valid @RequestBody PreviewRequest request) {
        merchants.require(request.merchantId());
        MethodType type = WireEnums.parse(MethodType.class, request.method(), "method");
        if (type == MethodType.MANDATE) {
            throw GatewayException.validation("method", "mandates are not routed");
        }
        UpiFlow flow = type == MethodType.UPI ? request.upiFlow() == null ? UpiFlow.INTENT
                : WireEnums.parse(UpiFlow.class, request.upiFlow(), "upi_flow") : null;
        PaymentMethod method = new PaymentMethod(type, flow, flow == UpiFlow.COLLECT ? "preview@routing" : null,
                request.bankCode(), null, request.methodProvider());
        CaptureMethod capture = request.captureMethod() == null ? CaptureMethod.AUTOMATIC
                : WireEnums.parse(CaptureMethod.class, request.captureMethod(), "capture_method");
        return routing.preview(new RoutingContext(request.merchantId(), method, Money.of(request.amount(), request.currency()),
                capture, merchants.activeProviders(request.merchantId()), Boolean.TRUE.equals(request.customerPhoneKnown())));
    }

    @GetMapping("/routing-decisions")
    public Map<String, Object> decisions(@RequestParam("merchant_id") String merchantId,
                                         @RequestParam(defaultValue = "100") int limit) {
        merchants.require(merchantId);
        if (limit < 1 || limit > 500) {
            throw GatewayException.validation("limit", "must be between 1 and 500");
        }
        return Map.of("data", decisions.recent(merchantId, limit));
    }

    @GetMapping("/routing-rules")
    public Map<String, Object> list() {
        return Map.of("data", repository.findAll());
    }

    @PostMapping("/routing-rules")
    @RequiresAdmin(AdminPermission.ROUTING_WRITE)
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
    @RequiresAdmin(AdminPermission.ROUTING_WRITE)
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
        RoutingStrategy shadow = request.shadowStrategy() == null ? null
                : WireEnums.parse(RoutingStrategy.class, request.shadowStrategy(), "shadow_strategy");
        if (shadow != null && shadow != RoutingStrategy.COST && shadow != RoutingStrategy.ADAPTIVE) {
            throw GatewayException.validation("shadow_strategy", "must be cost or adaptive");
        }
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
            request.allowFallback() == null || request.allowFallback(), version, createdAt, updatedAt, shadow);
    }
}
