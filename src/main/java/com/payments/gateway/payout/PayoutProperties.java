package com.payments.gateway.payout;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.payouts")
public record PayoutProperties(boolean enabled, String provider, int batchSize, Duration checkInterval, Duration reviewAfter) {
    public PayoutProperties {
        provider = provider == null ? "MOCK_PAYOUT" : provider;
        batchSize = batchSize == 0 ? 100 : batchSize;
        checkInterval = checkInterval == null ? Duration.ofMinutes(1) : checkInterval;
        reviewAfter = reviewAfter == null ? Duration.ofMinutes(30) : reviewAfter;
        if (provider.isBlank() || batchSize < 1 || batchSize > 1000 || checkInterval.compareTo(Duration.ofSeconds(1)) < 0
                || checkInterval.compareTo(Duration.ofHours(1)) > 0 || reviewAfter.compareTo(checkInterval) < 0) {
            throw new IllegalArgumentException("Invalid payout provider, worker batch, check interval or review deadline");
        }
    }
}