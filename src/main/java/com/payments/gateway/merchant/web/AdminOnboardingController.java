package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.KycDetails;
import com.payments.gateway.merchant.OnboardingService;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
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
@RequestMapping("/admin/v1/merchants/{merchantId}/kyc")
public class AdminOnboardingController {
    private final OnboardingService onboarding;

    public AdminOnboardingController(OnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @PostMapping
    @RequiresAdmin(AdminPermission.MERCHANTS_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public OnboardingService.View submit(@PathVariable String merchantId, @RequestBody KycDetails details,
                                        @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return onboarding.submit(merchantId, details, actor);
    }

    @GetMapping
    public OnboardingService.Overview list(@PathVariable String merchantId) {
        return onboarding.overview(merchantId);
    }

    @PostMapping("/{submissionId}/approve")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public OnboardingService.View approve(@PathVariable String merchantId, @PathVariable String submissionId,
                                         @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return onboarding.review(merchantId, submissionId, true, actor);
    }

    @PostMapping("/{submissionId}/reject")
    @RequiresAdmin(AdminPermission.FINANCE_WRITE)
    public OnboardingService.View reject(@PathVariable String merchantId, @PathVariable String submissionId,
                                        @RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor) {
        return onboarding.review(merchantId, submissionId, false, actor);
    }
}