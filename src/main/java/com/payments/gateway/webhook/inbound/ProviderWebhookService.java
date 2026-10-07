package com.payments.gateway.webhook.inbound;

import com.payments.gateway.payment.application.DisputeService;
import com.payments.gateway.payment.application.MandateService;
import com.payments.gateway.payment.application.PaymentOutcomeService;
import com.payments.gateway.payment.application.RefundService;
import com.payments.gateway.provider.MerchantAccountResolver;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.InboundWebhook;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.WebhookVerificationException;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.config.WorkerProperties;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Inbound PSP webhooks (inbox pattern): verify → store (dedupe on provider event id) → process inline; failures stay
 * in the inbox and are retried with backoff by {@link #processDue()}.
 */
@Service
public class ProviderWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ProviderWebhookService.class);
    private static final Duration LEASE = Duration.ofSeconds(60);

    public record ReceiveResult(int received, int duplicates) {
    }

    private final ProviderRegistry providers;
    private final MerchantAccountResolver accounts;
    private final ProviderWebhookRepository inbox;
    private final PaymentOutcomeService payments;
    private final RefundService refunds;
    private final DisputeService disputes;
    private final MandateService mandates;
    private final JsonCodec json;
    private final WorkerProperties workers;
    private final Clock clock;
    private final MeterRegistry meters;
    private final int maxAttempts;

    public ProviderWebhookService(ProviderRegistry providers, MerchantAccountResolver accounts,
                                  ProviderWebhookRepository inbox,
                                  PaymentOutcomeService payments, RefundService refunds, DisputeService disputes,
                                  MandateService mandates, JsonCodec json,
                                  WorkerProperties workers, Clock clock, MeterRegistry meters,
                                  @Value("${pg.webhooks.inbound.max-attempts:10}") int maxAttempts) {
        this.providers = providers;
        this.accounts = accounts;
        this.inbox = inbox;
        this.payments = payments;
        this.refunds = refunds;
        this.disputes = disputes;
        this.mandates = mandates;
        this.json = json;
        this.workers = workers;
        this.clock = clock;
        this.meters = meters;
        this.maxAttempts = maxAttempts;
        // Alerts watch these with increase(), which cannot see a series' first increment (ADR-027).
        for (PaymentProvider provider : providers.all()) {
            for (String result : List.of("invalid", "source_rejected", "retry")) {
                meters.counter("pg.webhooks.inbound", "provider", provider.code(), "result", result);
            }
        }
    }

    /**
     * {@code accountId} is set for the merchant account endpoint: the adapter verifies with that account's secret
     * and the events may only affect that merchant. Null is the provider-wide endpoint (platform-level secrets).
     */
    public ReceiveResult receive(String providerCode, String accountId, Map<String, String> headers, String body) {
        PaymentProvider provider = providers.find(providerCode)
                .orElseThrow(() -> GatewayException.notFound("Provider", providerCode));
        MerchantAccount account = null;
        if (accountId != null) {
            account = accounts.findById(accountId).filter(found -> found.providerCode().equals(providerCode))
                    .orElseThrow(() -> GatewayException.notFound("Webhook endpoint", providerCode + "/" + accountId));
        }
        Instant now = clock.instant();
        List<ProviderEvent> events;
        try {
            events = provider.parseWebhook(account, new InboundWebhook(headers, body, now));
        } catch (WebhookVerificationException e) {
            meters.counter("pg.webhooks.inbound", "provider", providerCode, "result", "invalid").increment();
            log.warn("Rejected webhook from {}{}: {}", providerCode, accountId == null ? "" : "/" + accountId, e.getMessage());
            throw new GatewayException(ErrorCode.INVALID_SIGNATURE, "Webhook verification failed");
        }
        String merchantScope = account == null ? null : account.merchantId();
        int received = 0;
        int duplicates = 0;
        for (ProviderEvent event : events) {
            String inboxId = Ids.newId("pwe");
            if (!inbox.insertIfAbsent(inboxId, providerCode, accountId, merchantScope, event.eventId(), event.eventType(),
                    body, json.write(event), now)) {
                duplicates++;
                meters.counter("pg.webhooks.inbound", "provider", providerCode, "result", "duplicate").increment();
                continue;
            }
            received++;
            process(inboxId, providerCode, merchantScope, event, 0);
        }
        return new ReceiveResult(received, duplicates);
    }

    public int purgeHandledBefore(Instant cutoff, int limit) {
        return inbox.deleteHandledBefore(cutoff, limit);
    }

    public int processDue() {
        Instant now = clock.instant();
        List<ProviderWebhookRepository.PendingEvent> due = inbox.claimDue(now, now.plus(LEASE), workers.batchSize());
        for (ProviderWebhookRepository.PendingEvent pending : due) {
            ProviderEvent event = json.read(pending.normalizedEvent(), ProviderEvent.class);
            process(pending.id(), pending.providerCode(), pending.merchantId(), event, pending.attempts());
        }
        return due.size();
    }

    private void process(String inboxId, String providerCode, String merchantScope, ProviderEvent event,
                         int previousAttempts) {
        try {
            boolean applied = switch (event.kind()) {
                case PAYMENT -> payments.applyProviderEvent(providerCode, merchantScope, event);
                case REFUND -> refunds.applyProviderEvent(providerCode, merchantScope, event);
                case DISPUTE -> disputes.applyProviderEvent(providerCode, merchantScope, event);
                case MANDATE -> mandates.applyProviderEvent(providerCode, merchantScope, event);
                case NOTIFICATION -> mandates.applyNotificationEvent(providerCode, merchantScope, event);
            };
            inbox.markDone(inboxId, applied ? "PROCESSED" : "IGNORED", clock.instant());
            meters.counter("pg.webhooks.inbound", "provider", providerCode, "result", applied ? "processed" : "ignored").increment();
        } catch (RuntimeException e) {
            int attempts = previousAttempts + 1;
            Instant next = attempts >= maxAttempts ? null
                    : clock.instant().plus(Duration.ofSeconds(Math.min(3600, 10L << Math.min(attempts, 9))));
            inbox.markForRetry(inboxId, attempts, next, e.getClass().getSimpleName() + ": " + e.getMessage());
            meters.counter("pg.webhooks.inbound", "provider", providerCode, "result", "retry").increment();
            log.warn("Processing webhook {} failed (attempt {}); {}", inboxId, attempts,
                    next == null ? "giving up" : "retrying at " + next, e);
        }
    }
}
