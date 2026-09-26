package com.payments.gateway.merchant.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-merchant API budgets, enforced per instance (ADR-011). Reads are GET/HEAD; everything else is a write. */
@ConfigurationProperties("pg.rate-limit")
public record RateLimitProperties(Boolean enabled, Limit read, Limit write) {

    public record Limit(double perSecond, int burst) {

        public Limit {
            if (perSecond <= 0 || burst < 1) {
                throw new IllegalArgumentException("rate limit needs per-second > 0 and burst >= 1");
            }
        }
    }

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        read = read == null ? new Limit(200, 400) : read;
        write = write == null ? new Limit(100, 200) : write;
    }
}
