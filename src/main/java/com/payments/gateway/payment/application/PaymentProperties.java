package com.payments.gateway.payment.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.payments")
public record PaymentProperties(Duration defaultExpiry, Duration processingGrace, Duration authorizationTtl,
                                int maxAttempts, List<String> supportedCurrencies) {

    public PaymentProperties {
        defaultExpiry = defaultExpiry == null ? Duration.ofMinutes(15) : defaultExpiry;
        processingGrace = processingGrace == null ? Duration.ofMinutes(30) : processingGrace;
        authorizationTtl = authorizationTtl == null ? Duration.ofDays(5) : authorizationTtl;
        maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
        supportedCurrencies = supportedCurrencies == null || supportedCurrencies.isEmpty() ? List.of("INR") : List.copyOf(supportedCurrencies);
    }
}
