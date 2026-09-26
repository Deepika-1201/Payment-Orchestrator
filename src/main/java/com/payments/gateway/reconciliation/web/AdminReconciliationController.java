package com.payments.gateway.reconciliation.web;

import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.reconciliation.ReconciliationService;
import com.payments.gateway.reconciliation.ReconciliationService.ExceptionView;
import com.payments.gateway.reconciliation.ReconciliationService.RunSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/reconciliation")
public class AdminReconciliationController {

    public record RunRequest(@NotBlank String merchantId, @NotBlank String provider, @NotNull Instant from,
                             @NotNull Instant to) {
    }

    public record ResolveRequest(@NotBlank @Size(max = 1000) String resolution) {
    }

    private final ReconciliationService reconciliation;

    public AdminReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public RunSummary run(@Valid @RequestBody RunRequest request) {
        return reconciliation.run(request.merchantId(), request.provider(), request.from(), request.to());
    }

    @GetMapping("/runs/{id}")
    public RunSummary get(@PathVariable String id) {
        return reconciliation.get(id);
    }

    @GetMapping("/exceptions")
    public Map<String, List<ExceptionView>> exceptions(@RequestParam(value = "status", required = false) String status,
                                                       @RequestParam(value = "merchant_id", required = false) String merchantId) {
        return Map.of("data", reconciliation.exceptions(status, merchantId));
    }

    @PostMapping("/exceptions/{id}/resolve")
    public ExceptionView resolve(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor, @PathVariable String id,
                                 @Valid @RequestBody ResolveRequest request) {
        return reconciliation.resolve(id, request.resolution(), actor);
    }
}
