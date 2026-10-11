package com.payments.gateway.treasury;

import java.time.LocalDate;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.treasury")
public record TreasuryProperties(Set<LocalDate> holidays, int batchSize) {
    public TreasuryProperties {
        holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
        batchSize = batchSize == 0 ? 100 : batchSize;
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("pg.treasury.batch-size must be 1-1000");
        }
    }
}