package com.payments.gateway.shared.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.webhooks.outbound")
public record OutboundWebhookProperties(Duration connectTimeout, Duration readTimeout, boolean allowPrivateTargets,
                                        boolean requireHttps) {

    public OutboundWebhookProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }
}
