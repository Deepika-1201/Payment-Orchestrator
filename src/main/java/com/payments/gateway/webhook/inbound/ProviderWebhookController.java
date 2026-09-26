package com.payments.gateway.webhook.inbound;

import com.payments.gateway.shared.error.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PSP webhook endpoints, authenticated by the provider's signature, not by API keys. PSPs where each merchant owns
 * the account are configured with the account endpoint (ADR-014); the provider-wide one is for platform-level secrets.
 */
@RestController
public class ProviderWebhookController {

    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final ProviderWebhookService service;

    public ProviderWebhookController(ProviderWebhookService service) {
        this.service = service;
    }

    @PostMapping("/v1/webhooks/providers/{provider}")
    public Map<String, Object> receive(@PathVariable String provider, HttpServletRequest request) throws IOException {
        return receive(provider, null, request);
    }

    @PostMapping("/v1/webhooks/providers/{provider}/{account}")
    public Map<String, Object> receiveForAccount(@PathVariable String provider, @PathVariable String account,
                                                 HttpServletRequest request) throws IOException {
        return receive(provider, account, request);
    }

    private Map<String, Object> receive(String provider, String account, HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw GatewayException.validation("body", "must not exceed 256 KB");
        }
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        ProviderWebhookService.ReceiveResult result = service.receive(provider, account, headers,
                new String(bytes, StandardCharsets.UTF_8));
        return Map.of("received", result.received(), "duplicates", result.duplicates());
    }
}
