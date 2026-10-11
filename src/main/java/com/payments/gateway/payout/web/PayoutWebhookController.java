package com.payments.gateway.payout.web;

import com.payments.gateway.payout.PayoutWebhookService;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/webhooks/payouts")
public class PayoutWebhookController {
    private final PayoutWebhookService webhooks;

    public PayoutWebhookController(PayoutWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/{provider}")
    public PayoutWebhookService.Received receive(@PathVariable String provider, @RequestHeader Map<String, String> headers,
                                                  @RequestBody String body) {
        return webhooks.receive(provider, headers, body);
    }
}