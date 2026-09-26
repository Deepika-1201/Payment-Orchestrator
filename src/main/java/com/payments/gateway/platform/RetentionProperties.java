package com.payments.gateway.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** How long operational rows are kept (ADR-015). Financial records are never deleted by the application. */
@ConfigurationProperties("pg.retention")
public record RetentionProperties(Duration providerWebhooks, Duration merchantEvents, Integer batchSize,
                                  Integer maxBatchesPerRun) {

    public RetentionProperties {
        providerWebhooks = providerWebhooks == null ? Duration.ofDays(180) : providerWebhooks;
        merchantEvents = merchantEvents == null ? Duration.ofDays(90) : merchantEvents;
        batchSize = batchSize == null ? 5_000 : batchSize;
        maxBatchesPerRun = maxBatchesPerRun == null ? 200 : maxBatchesPerRun;
    }
}
