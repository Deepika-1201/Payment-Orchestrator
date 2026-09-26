package com.payments.gateway.payment.web;

import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.payment.application.ReviewService;
import com.payments.gateway.payment.application.ReviewService.ReviewItem;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.web.AdminPermission;
import com.payments.gateway.shared.web.RequiresAdmin;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operations' manual review queue (ADR-016). */
@RestController
@RequestMapping("/admin/v1/reviews")
public class AdminReviewController {

    public record ResolveRequest(@NotBlank @Size(max = 1000) String note) {
    }

    private final ReviewService reviews;

    public AdminReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    public Map<String, List<ReviewItem>> list(@RequestParam(value = "kind", required = false) String kind,
                                              @RequestParam(value = "merchant_id", required = false) String merchantId,
                                              @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (limit < 1 || limit > 500) {
            throw GatewayException.validation("limit", "must be between 1 and 500");
        }
        ReviewService.Kind parsed = kind == null ? null : WireEnums.parse(ReviewService.Kind.class, kind, "kind");
        return Map.of("data", reviews.open(parsed, merchantId, limit));
    }

    @PostMapping("/attempts/{id}/resolve")
    @RequiresAdmin(AdminPermission.OPERATIONS_WRITE)
    public ReviewItem resolveAttempt(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                     @PathVariable String id, @Valid @RequestBody ResolveRequest request) {
        return reviews.resolveAttempt(id, request.note(), actor);
    }

    @PostMapping("/refunds/{id}/resolve")
    @RequiresAdmin(AdminPermission.OPERATIONS_WRITE)
    public ReviewItem resolveRefund(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                    @PathVariable String id, @Valid @RequestBody ResolveRequest request) {
        return reviews.resolveRefund(id, request.note(), actor);
    }

    @PostMapping("/disputes/{id}/resolve")
    @RequiresAdmin(AdminPermission.OPERATIONS_WRITE)
    public ReviewItem resolveDispute(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor,
                                     @PathVariable String id, @Valid @RequestBody ResolveRequest request) {
        return reviews.resolveDispute(id, request.note(), actor);
    }
}
