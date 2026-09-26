package com.payments.gateway.ledger.web;

import com.payments.gateway.ledger.LedgerAccountType;
import com.payments.gateway.ledger.LedgerAdjustmentService;
import com.payments.gateway.ledger.LedgerAdjustmentService.AdjustmentRequest;
import com.payments.gateway.ledger.LedgerAdjustmentService.AdjustmentView;
import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

/** Maker-checker manual ledger adjustments (ADR-024). */
@RestController
@RequestMapping("/admin/v1/ledger/adjustments")
public class AdminLedgerAdjustmentController {

    public record CreateRequest(@NotBlank String merchantId, @NotBlank String provider, @NotBlank String debitAccount,
                                @NotBlank String creditAccount, @Min(1) @Max(100_000_000_000L) long amount,
                                @Pattern(regexp = "[A-Z]{3}") String currency, @NotBlank @Size(max = 1000) String reason,
                                @Size(max = 200) String reference) {
    }

    public record DecisionRequest(@NotBlank @Size(max = 1000) String note) {
    }

    private final LedgerAdjustmentService adjustments;

    public AdminLedgerAdjustmentController(LedgerAdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @PostMapping
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public AdjustmentView request(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                  @Valid @RequestBody CreateRequest request) {
        return adjustments.request(new AdjustmentRequest(request.merchantId(), request.provider(),
                WireEnums.parse(LedgerAccountType.class, request.debitAccount(), "debit_account"),
                WireEnums.parse(LedgerAccountType.class, request.creditAccount(), "credit_account"),
                Money.of(request.amount(), request.currency() == null ? "INR" : request.currency()), request.reason(),
                request.reference()), actor);
    }

    @GetMapping
    public Map<String, List<AdjustmentView>> list(@RequestParam(value = "status", required = false) String status,
                                                  @RequestParam(value = "merchant_id", required = false) String merchantId) {
        return Map.of("data", adjustments.list(status, merchantId));
    }

    @GetMapping("/{id}")
    public AdjustmentView get(@PathVariable String id) {
        return adjustments.get(id);
    }

    @PostMapping("/{id}/approve")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public AdjustmentView approve(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor, @PathVariable String id,
                                  @Valid @RequestBody DecisionRequest request) {
        return adjustments.approve(id, request.note(), actor);
    }

    @PostMapping("/{id}/reject")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public AdjustmentView reject(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor, @PathVariable String id,
                                 @Valid @RequestBody DecisionRequest request) {
        return adjustments.reject(id, request.note(), actor);
    }
}
