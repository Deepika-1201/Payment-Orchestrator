package com.payments.gateway.reconciliation;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reconciliation settings (ADR-017).
 *
 * @param exceptionSla time from opening an exception until it counts as overdue
 * @param zone         business time zone: the daily run reconciles the previous local day, and reports use its dates
 * @param catchUpDays  how many recent days the daily run looks back over to retry runs that failed (ADR-032)
 */
@ConfigurationProperties("pg.reconciliation")
public record ReconciliationProperties(Duration exceptionSla, ZoneId zone, Integer catchUpDays) {

    public ReconciliationProperties {
        exceptionSla = exceptionSla == null ? Duration.ofHours(48) : exceptionSla;
        zone = zone == null ? ZoneId.of("Asia/Kolkata") : zone;
        catchUpDays = catchUpDays == null ? 3 : catchUpDays;
    }
}
