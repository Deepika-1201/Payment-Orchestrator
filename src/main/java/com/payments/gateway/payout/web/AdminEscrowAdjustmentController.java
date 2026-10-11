package com.payments.gateway.payout.web;

import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.payout.EscrowAdjustmentService;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/admin/v1/treasury/adjustments")
public class AdminEscrowAdjustmentController {
    public record Request(@NotBlank @Size(max = 128) String reference, @NotBlank String kind, @Positive long amount,
                          @NotBlank String statementId, @NotBlank String bankEntryId, @NotBlank @Size(max = 1000) String reason) {
    }

    private final EscrowAdjustmentService adjustments;

    public AdminEscrowAdjustmentController(EscrowAdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @PostMapping
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public EscrowAdjustmentService.Adjustment request(@Valid @RequestBody Request request,
            @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return adjustments.request(new EscrowAdjustmentService.Command(request.reference(),
                WireEnums.parse(EscrowAdjustmentService.Kind.class, request.kind(), "kind"), request.amount(), request.statementId(),
                request.bankEntryId(), request.reason()), actor);
    }

    @GetMapping("/{id}")
    public EscrowAdjustmentService.Adjustment get(@PathVariable String id) {
        return adjustments.get(id);
    }

    @PostMapping("/{id}/approve")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public EscrowAdjustmentService.Adjustment approve(@PathVariable String id, @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return adjustments.decide(id, true, actor);
    }

    @PostMapping("/{id}/reject")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public EscrowAdjustmentService.Adjustment reject(@PathVariable String id, @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return adjustments.decide(id, false, actor);
    }
}