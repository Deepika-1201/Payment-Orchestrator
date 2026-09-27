package com.payments.gateway.webhook.inbound;

import com.payments.gateway.shared.net.CidrRange;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional source-IP allowlists for PSP webhooks, per provider code (ADR-022). A provider without an entry accepts
 * any source; signatures are always verified either way. Client IPs come from {@code X-Forwarded-For} only when
 * the peer is a trusted internal proxy (the ALB), see {@code server.forward-headers-strategy}.
 */
@ConfigurationProperties("pg.webhooks.inbound")
public record InboundWebhookProperties(Map<String, List<String>> allowedSources) {

    public InboundWebhookProperties {
        allowedSources = allowedSources == null ? Map.of() : allowedSources.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> normalize(e.getKey()), e -> List.copyOf(e.getValue())));
        allowedSources.values().forEach(cidrs -> cidrs.forEach(CidrRange::parse));
    }

    public boolean permits(String providerCode, String remoteAddress) {
        List<String> cidrs = allowedSources.get(normalize(providerCode));
        return cidrs == null || cidrs.stream().map(CidrRange::parse).anyMatch(range -> range.contains(remoteAddress));
    }

    /** The provider code as registered; callers label metrics with it so path spelling cannot add series. */
    public static String normalize(String providerCode) {
        return providerCode.toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
