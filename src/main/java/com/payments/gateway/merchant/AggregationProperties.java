package com.payments.gateway.merchant;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.aggregation")
public record AggregationProperties(boolean enabled, boolean collectionsEnabled, String platformMerchantId,
                                     String authorizationReference) {
    public AggregationProperties {
        if (enabled && (platformMerchantId == null || platformMerchantId.isBlank())) {
            throw new IllegalArgumentException("pg.aggregation.platform-merchant-id is required when aggregation is enabled");
        }
        if (collectionsEnabled && !enabled) {
            throw new IllegalArgumentException("pg.aggregation.collections-enabled requires aggregation to be enabled");
        }
    }
}