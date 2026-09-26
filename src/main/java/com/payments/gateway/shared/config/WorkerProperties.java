package com.payments.gateway.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.workers")
public record WorkerProperties(boolean enabled, int batchSize) {

    public WorkerProperties {
        batchSize = batchSize <= 0 ? 50 : batchSize;
    }
}
