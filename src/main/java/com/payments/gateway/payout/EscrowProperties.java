package com.payments.gateway.payout;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pg.escrow")
public record EscrowProperties(String accountReference, Duration statementAgeLimit, @DefaultValue("true") boolean requireCurrentStatement) {
    public EscrowProperties {
        accountReference = accountReference == null ? "primary-escrow" : accountReference;
        statementAgeLimit = statementAgeLimit == null ? Duration.ofHours(36) : statementAgeLimit;
        if (accountReference.isBlank() || accountReference.length() > 200 || statementAgeLimit.compareTo(Duration.ofHours(1)) < 0
                || statementAgeLimit.compareTo(Duration.ofHours(48)) > 0) {
            throw new IllegalArgumentException("Invalid escrow account reference or statement age limit");
        }
    }
}