package com.payments.gateway.platform;

import com.payments.gateway.checkout.CheckoutService;
import com.payments.gateway.idempotency.IdempotencyService;
import com.payments.gateway.webhook.inbound.ProviderWebhookService;
import com.payments.gateway.webhook.outbound.WebhookDeliveryWorker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deletes expired operational rows in short batches, each its own statement, so no long transaction or lock is held.
 * A run stops after {@code maxBatchesPerRun} batches per table and continues on the next run.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final IdempotencyService idempotency;
    private final CheckoutService checkout;
    private final ProviderWebhookService providerWebhooks;
    private final WebhookDeliveryWorker merchantWebhooks;
    private final RetentionProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    public RetentionJob(IdempotencyService idempotency, CheckoutService checkout, ProviderWebhookService providerWebhooks,
                        WebhookDeliveryWorker merchantWebhooks, RetentionProperties properties, Clock clock,
                        MeterRegistry meters) {
        this.idempotency = idempotency;
        this.checkout = checkout;
        this.providerWebhooks = providerWebhooks;
        this.merchantWebhooks = merchantWebhooks;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    /** Returns rows deleted per data set. */
    public Map<String, Integer> run() {
        Instant now = clock.instant();
        Instant providerCutoff = now.minus(properties.providerWebhooks());
        Instant merchantCutoff = now.minus(properties.merchantEvents());
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("idempotency_records", purge(idempotency::purgeExpired));
        deleted.put("checkout_sessions", purge(checkout::purgeExpired));
        deleted.put("provider_webhook_events", purge(limit -> providerWebhooks.purgeHandledBefore(providerCutoff, limit)));
        deleted.put("merchant_events_and_deliveries", purge(limit -> merchantWebhooks.purgeBefore(merchantCutoff, limit)));
        deleted.forEach((table, count) -> meters.counter("pg.retention.deleted", "table", table).increment(count));
        if (deleted.values().stream().anyMatch(count -> count > 0)) {
            log.info("Retention purge deleted {}", deleted);
        }
        return deleted;
    }

    private int purge(IntUnaryOperator batch) {
        int total = 0;
        for (int i = 0; i < properties.maxBatchesPerRun(); i++) {
            int deleted = batch.applyAsInt(properties.batchSize());
            total += deleted;
            if (deleted < properties.batchSize()) {
                break;
            }
        }
        return total;
    }
}
