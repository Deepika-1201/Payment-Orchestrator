package com.payments.gateway.webhook.outbound;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.shared.config.OutboundWebhookProperties;
import com.payments.gateway.shared.config.WorkerProperties;
import com.payments.gateway.shared.net.UrlSafetyValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers merchant webhooks at least once with exponential backoff (±20% jitter) for ~47h, then dead-letters.
 * Each batch is delivered concurrently on virtual threads so one slow endpoint cannot stall the others.
 */
@Component
public class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3),
            Duration.ofHours(6), Duration.ofHours(12), Duration.ofHours(24));
    static final int MAX_ATTEMPTS = RETRY_DELAYS.size() + 1;
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final MerchantWebhookRepository repository;
    private final MerchantDirectory merchants;
    private final UrlSafetyValidator urlValidator;
    private final OutboundWebhookProperties properties;
    private final WorkerProperties workers;
    private final Clock clock;
    private final MeterRegistry meters;
    private final HttpClient http;

    public WebhookDeliveryWorker(MerchantWebhookRepository repository, MerchantDirectory merchants,
                                 UrlSafetyValidator urlValidator, OutboundWebhookProperties properties,
                                 WorkerProperties workers, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.merchants = merchants;
        this.urlValidator = urlValidator;
        this.properties = properties;
        this.workers = workers;
        this.clock = clock;
        this.meters = meters;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public int deliverDue() {
        Instant now = clock.instant();
        List<MerchantWebhookRepository.DueDelivery> due = repository.claimDue(now, now.plus(LEASE), workers.batchSize());
        if (due.isEmpty()) {
            return 0;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (MerchantWebhookRepository.DueDelivery delivery : due) {
                executor.submit(() -> deliver(delivery));
            }
        }
        return due.size();
    }

    private void deliver(MerchantWebhookRepository.DueDelivery delivery) {
        int attempt = delivery.attemptCount() + 1;
        try {
            Optional<String> unsafe = urlValidator.check(delivery.url());
            if (unsafe.isPresent()) {
                fail(delivery, attempt, null, "unsafe webhook URL: " + unsafe.get());
                return;
            }
            Optional<String> secret = merchants.webhookSecret(delivery.merchantId());
            if (secret.isEmpty()) {
                fail(delivery, attempt, null, "merchant has no webhook secret");
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.url()))
                    .timeout(properties.readTimeout())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "PaymentGateway-Webhooks/1.0")
                    .header("PG-Event-Id", delivery.eventId())
                    .header("PG-Event-Type", delivery.eventType())
                    .header(WebhookSigner.HEADER, WebhookSigner.sign(secret.get(), clock.instant().getEpochSecond(), delivery.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.payload()))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                repository.markSucceeded(delivery.id(), attempt, response.statusCode(), clock.instant());
                meters.counter("pg.webhook.deliveries", "result", "succeeded").increment();
            } else {
                fail(delivery, attempt, response.statusCode(), "HTTP " + response.statusCode());
            }
        } catch (IOException | IllegalArgumentException e) {
            fail(delivery, attempt, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(delivery, attempt, null, "interrupted");
        } catch (RuntimeException e) {
            log.error("Unexpected error delivering webhook {}", delivery.id(), e);
            fail(delivery, attempt, null, e.getClass().getSimpleName());
        }
    }

    private void fail(MerchantWebhookRepository.DueDelivery delivery, int attempt, Integer status, String error) {
        Instant next = attempt >= MAX_ATTEMPTS ? null : clock.instant().plus(jitter(RETRY_DELAYS.get(attempt - 1)));
        repository.markFailed(delivery.id(), attempt, next, status, error, clock.instant());
        meters.counter("pg.webhook.deliveries", "result", next == null ? "dead" : "retry").increment();
        if (next == null) {
            log.warn("Webhook delivery {} for event {} is DEAD after {} attempts: {}", delivery.id(), delivery.eventId(), attempt, error);
        }
    }

    private static Duration jitter(Duration base) {
        double factor = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis((long) (base.toMillis() * factor));
    }
}
