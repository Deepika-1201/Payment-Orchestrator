package com.payments.gateway.webhook.outbound;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.events.MerchantEventRequested;
import com.payments.gateway.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Transactional outbox writer: runs synchronously inside the state-change transaction, so an event exists if and
 * only if the change committed.
 */
@Component
public class MerchantEventRecorder {

    record EventEnvelope(String id, String type, Instant createdAt, Map<String, Object> data) {
    }

    private final MerchantWebhookRepository repository;
    private final MerchantDirectory merchants;
    private final JsonCodec json;
    private final MeterRegistry meters;

    public MerchantEventRecorder(MerchantWebhookRepository repository, MerchantDirectory merchants, JsonCodec json,
                                 MeterRegistry meters) {
        this.repository = repository;
        this.merchants = merchants;
        this.json = json;
        this.meters = meters;
    }

    @EventListener
    public void record(MerchantEventRequested event) {
        String eventId = Ids.newId("evt");
        String payload = json.write(new EventEnvelope(eventId, event.type(), event.occurredAt(),
                Map.of("object", event.payload())));
        repository.insertEvent(eventId, event.merchantId(), event.type(), event.resourceId(), payload, event.occurredAt());
        String url = merchants.require(event.merchantId()).webhookUrl();
        if (url != null) {
            repository.insertDelivery(Ids.newId("whd"), eventId, event.merchantId(), url, event.occurredAt());
        }
        meters.counter("pg.merchant.events", "type", event.type()).increment();
    }
}
