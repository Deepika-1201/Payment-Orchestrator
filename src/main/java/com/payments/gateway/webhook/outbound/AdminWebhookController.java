package com.payments.gateway.webhook.outbound;

import com.payments.gateway.merchant.web.AdminAuthFilter;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.GatewayException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/webhook-deliveries")
public class AdminWebhookController {

    private final MerchantWebhookRepository repository;
    private final AuditLogger audit;
    private final Clock clock;

    public AdminWebhookController(MerchantWebhookRepository repository, AuditLogger audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    public Map<String, List<MerchantWebhookRepository.DeliveryView>> list(@RequestParam("resource_id") String resourceId) {
        return Map.of("data", repository.findByResource(resourceId));
    }

    @PostMapping("/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> replay(@RequestAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE) String actor, @PathVariable String id) {
        if (repository.findStatus(id).isEmpty()) {
            throw GatewayException.notFound("Webhook delivery", id);
        }
        if (!repository.requeue(id, clock.instant())) {
            throw GatewayException.invalidState("Delivery " + id + " is already pending");
        }
        audit.record("ADMIN", actor, "webhook_delivery.replayed", "webhook_delivery", id, Map.of());
        return Map.of("id", id, "status", "pending");
    }
}
