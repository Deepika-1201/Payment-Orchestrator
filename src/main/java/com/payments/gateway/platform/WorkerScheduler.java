package com.payments.gateway.platform;

import com.payments.gateway.idempotency.IdempotencyService;
import com.payments.gateway.payment.application.ExpiryJob;
import com.payments.gateway.payment.application.StatusResolver;
import com.payments.gateway.reconciliation.ReconciliationService;
import com.payments.gateway.webhook.inbound.ProviderWebhookService;
import com.payments.gateway.webhook.outbound.WebhookDeliveryWorker;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background jobs of the {@code worker} role. All jobs are safe to run on any number of replicas. */
@Component
@ConditionalOnProperty(prefix = "pg.workers", name = "enabled", havingValue = "true")
public class WorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerScheduler.class);

    private final StatusResolver statusResolver;
    private final ExpiryJob expiryJob;
    private final ProviderWebhookService inbox;
    private final WebhookDeliveryWorker deliveries;
    private final IdempotencyService idempotency;
    private final ReconciliationService reconciliation;

    public WorkerScheduler(StatusResolver statusResolver, ExpiryJob expiryJob, ProviderWebhookService inbox,
                           WebhookDeliveryWorker deliveries, IdempotencyService idempotency,
                           ReconciliationService reconciliation) {
        this.statusResolver = statusResolver;
        this.expiryJob = expiryJob;
        this.inbox = inbox;
        this.deliveries = deliveries;
        this.idempotency = idempotency;
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    void statusChecks() {
        run("status-checks", () -> {
            statusResolver.resolveDueAttempts();
            statusResolver.resolveDueRefunds();
        });
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    void expiry() {
        run("expiry", expiryJob::expireDue);
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    void inboxRetries() {
        run("inbox-retries", inbox::processDue);
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    void webhookDeliveries() {
        run("webhook-deliveries", deliveries::deliverDue);
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    void purgeIdempotencyKeys() {
        run("idempotency-purge", () -> idempotency.purgeExpired(10_000));
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Kolkata")
    void dailyReconciliation() {
        run("reconciliation", () -> reconciliation.runForPreviousDay(ZoneId.of("Asia/Kolkata")));
    }

    private static void run(String job, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Worker job {} failed", job, e);
        }
    }
}
