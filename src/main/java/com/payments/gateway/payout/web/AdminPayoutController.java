package com.payments.gateway.payout.web;

import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.payout.PayoutService;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/merchants/{merchantId}")
public class AdminPayoutController {
    private final PayoutService payouts;

    public AdminPayoutController(PayoutService payouts) {
        this.payouts = payouts;
    }

    @PostMapping("/settlement-batches/{batchId}/payout")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public PayoutService.View queue(@PathVariable String merchantId, @PathVariable String batchId,
                                   @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return payouts.queue(merchantId, batchId, actor);
    }

    @GetMapping("/payouts")
    public Map<String, List<PayoutService.View>> list(@PathVariable String merchantId) {
        return Map.of("data", payouts.list(merchantId));
    }

    @GetMapping("/payouts/{payoutId}")
    public PayoutService.View get(@PathVariable String merchantId, @PathVariable String payoutId) {
        return payouts.get(merchantId, payoutId);
    }

    @PostMapping("/payouts/{payoutId}/check")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public PayoutService.View check(@PathVariable String merchantId, @PathVariable String payoutId) {
        return payouts.check(merchantId, payoutId);
    }

    @PostMapping("/payouts/{payoutId}/cancel")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public PayoutService.View cancel(@PathVariable String merchantId, @PathVariable String payoutId,
                                    @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return payouts.cancel(merchantId, payoutId, actor);
    }
}