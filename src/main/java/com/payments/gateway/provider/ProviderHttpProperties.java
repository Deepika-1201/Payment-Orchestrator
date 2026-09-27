package com.payments.gateway.provider;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Timeouts for real PSP HTTP calls. A read timeout means the outcome is unknown (ADR-005). */
@ConfigurationProperties("pg.providers.http")
public record ProviderHttpProperties(@DefaultValue("2s") Duration connectTimeout,
                                     @DefaultValue("10s") Duration readTimeout) {
}
