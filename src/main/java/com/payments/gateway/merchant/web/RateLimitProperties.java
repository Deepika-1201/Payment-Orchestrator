package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Default per-merchant budgets; a merchant may have its own override (ADR-020). */
@ConfigurationProperties("pg.rate-limit")
public record RateLimitProperties(Boolean enabled, RateLimit read, RateLimit write) {

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        read = read == null ? new RateLimit(200, 400) : read;
        write = write == null ? new RateLimit(100, 200) : write;
    }
}
