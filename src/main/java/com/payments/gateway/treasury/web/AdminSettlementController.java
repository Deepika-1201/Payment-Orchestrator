package com.payments.gateway.treasury.web;

import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import com.payments.gateway.treasury.SettlementService;
import com.payments.gateway.treasury.TreasuryModel;
import com.payments.gateway.treasury.TreasuryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1")
public class AdminSettlementController {
    public record PolicyRequest(Integer businessDays, Long reserveTarget, Boolean held,
                                 @NotBlank @Size(max = 200) String bankRegistrationReference,
                                 @NotBlank @Size(max = 1000) String reason) {
    }

    public record Prepare(@NotBlank @Size(max = 128) String reference) {
    }

    public record Decision(@NotBlank @Size(max = 1000) String reason) {
    }

    private final SettlementService settlements;
    private final TreasuryService treasury;

    public AdminSettlementController(SettlementService settlements, TreasuryService treasury) {
        this.settlements = settlements;
        this.treasury = treasury;
    }

    @PostMapping("/merchants/{merchantId}/settlement-policy")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public TreasuryModel.Policy policy(@PathVariable String merchantId, @Valid @RequestBody PolicyRequest command,
                                       @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return settlements.policy(merchantId, new SettlementService.PolicyCommand(command.businessDays() == null ? 1 : command.businessDays(),
                command.reserveTarget() == null ? 0 : command.reserveTarget(), Boolean.TRUE.equals(command.held()),
                command.bankRegistrationReference(), command.reason()), actor);
    }

    @GetMapping("/merchants/{merchantId}/settlement-policy")
    public TreasuryModel.Policy policy(@PathVariable String merchantId) {
        return settlements.policy(merchantId);
    }

    @PostMapping("/merchants/{merchantId}/settlement-batches")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public TreasuryModel.Batch prepare(@PathVariable String merchantId, @Valid @RequestBody Prepare command,
                                       @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return settlements.prepare(merchantId, command.reference(), actor);
    }

    @GetMapping("/merchants/{merchantId}/settlement-batches")
    public Map<String, List<TreasuryModel.Batch>> list(@PathVariable String merchantId) {
        return Map.of("data", settlements.list(merchantId));
    }

    @GetMapping("/merchants/{merchantId}/settlement-batches/{batchId}")
    public TreasuryModel.Batch get(@PathVariable String merchantId, @PathVariable String batchId) {
        return settlements.get(merchantId, batchId);
    }

    @PostMapping("/merchants/{merchantId}/settlement-batches/{batchId}/approve")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public TreasuryModel.Batch approve(@PathVariable String merchantId, @PathVariable String batchId,
            @Valid @RequestBody Decision command, @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return settlements.decide(merchantId, batchId, true, command.reason(), actor);
    }

    @PostMapping("/merchants/{merchantId}/settlement-batches/{batchId}/reject")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public TreasuryModel.Batch reject(@PathVariable String merchantId, @PathVariable String batchId,
            @Valid @RequestBody Decision command, @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return settlements.decide(merchantId, batchId, false, command.reason(), actor);
    }

    @GetMapping("/merchants/{merchantId}/treasury")
    public TreasuryModel.Balance merchant(@PathVariable String merchantId) {
        return treasury.balance(merchantId);
    }

    @GetMapping("/treasury/escrow")
    public TreasuryModel.Balance escrow() {
        return treasury.balance(null);
    }
}