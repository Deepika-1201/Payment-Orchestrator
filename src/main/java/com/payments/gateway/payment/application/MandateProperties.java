package com.payments.gateway.payment.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Recurring payment settings (LLD §18.7). */
@ConfigurationProperties("pg.mandates")
public record MandateProperties(Integer maxRetries, Duration retryInterval, Duration notificationLead,
                                Duration notifyAhead, Duration notificationCheckInterval, Duration notificationTimeout,
                                Duration authorizationWindow, Long frictionlessDebitLimit, Duration defaultTerm) {

    /** RBI floor for card and UPI debits: at least 24 hours between the pre-debit notification and the debit. */
    public static final Duration MINIMUM_NOTIFICATION_LEAD = Duration.ofHours(24);

    public MandateProperties {
        maxRetries = maxRetries == null ? 3 : maxRetries;
        retryInterval = retryInterval == null ? Duration.ofHours(24) : retryInterval;
        notificationLead = notificationLead == null ? MINIMUM_NOTIFICATION_LEAD : notificationLead;
        notifyAhead = notifyAhead == null ? Duration.ofHours(26) : notifyAhead;
        notificationCheckInterval = notificationCheckInterval == null ? Duration.ofMinutes(5) : notificationCheckInterval;
        notificationTimeout = notificationTimeout == null ? Duration.ofHours(48) : notificationTimeout;
        authorizationWindow = authorizationWindow == null ? Duration.ofHours(24) : authorizationWindow;
        frictionlessDebitLimit = frictionlessDebitLimit == null ? 1_500_000L : frictionlessDebitLimit;
        defaultTerm = defaultTerm == null ? Duration.ofDays(3650) : defaultTerm;
        if (maxRetries < 0) {
            throw new IllegalArgumentException("pg.mandates.max-retries must not be negative");
        }
        if (notificationLead.compareTo(MINIMUM_NOTIFICATION_LEAD) < 0) {
            throw new IllegalArgumentException("pg.mandates.notification-lead must be at least 24h (RBI pre-debit notice)");
        }
    }

    /** The first attempt plus the retries. */
    public int attemptLimit() {
        return 1 + maxRetries;
    }
}
