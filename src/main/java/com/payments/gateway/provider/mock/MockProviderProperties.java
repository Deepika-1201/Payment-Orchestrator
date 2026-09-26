package com.payments.gateway.provider.mock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.providers.mock")
public record MockProviderProperties(boolean enabled, Duration latency, String webhookSecret, String publicBaseUrl) {

    public MockProviderProperties {
        latency = latency == null ? Duration.ZERO : latency;
        publicBaseUrl = publicBaseUrl == null ? "http://localhost:8080" : publicBaseUrl;
    }
}
