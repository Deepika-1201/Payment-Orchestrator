package com.payments.gateway.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.idempotency")
public record IdempotencyProperties(Duration ttl, Duration lease) {

    public IdempotencyProperties {
        ttl = ttl == null ? Duration.ofDays(7) : ttl;
        lease = lease == null ? Duration.ofSeconds(30) : lease;
    }
}
