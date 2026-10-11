package com.payments.gateway.payout;

import com.payments.gateway.payout.spi.PayoutProvider;
import com.payments.gateway.payout.spi.PayoutProvider.Event;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.json.JsonCodec;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PayoutWebhookService {
    private static final Logger log = LoggerFactory.getLogger(PayoutWebhookService.class);

    public record Received(int received, int duplicates) {
    }

    private final PayoutProviders providers;
    private final PayoutRepository repository;
    private final PayoutService payouts;
    private final PayoutProperties properties;
    private final SecretCipher cipher;
    private final JsonCodec json;
    private final Clock clock;

    public PayoutWebhookService(PayoutProviders providers, PayoutRepository repository, PayoutService payouts,
                                PayoutProperties properties, SecretCipher cipher, JsonCodec json, Clock clock) {
        this.providers = providers;
        this.repository = repository;
        this.payouts = payouts;
        this.properties = properties;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
    }

    public Received receive(String provider, Map<String, String> headers, String body) {
        Event event;
        PayoutProvider adapter = providers.find(provider).orElseThrow(() -> GatewayException.notFound("Payout provider", provider));
        try {
            event = adapter.parseWebhook(headers, body);
        } catch (RuntimeException exception) {
            throw new GatewayException(ErrorCode.INVALID_SIGNATURE, "Payout webhook verification failed");
        }
        if (event == null || event.referenceId() == null || event.id() == null || event.result() == null) {
            return new Received(0, 0);
        }
        String id = Ids.newId("pwi");
        String encoded = json.write(event);
        var stored = new PayoutRepository.Inbox(id, provider, event.referenceId(), cipher.encrypt(encoded, PayoutRepository.inboxContext(id)),
                Hashing.sha256(encoded), 0);
        if (!repository.inbox(stored, event.id(), clock.instant())) {
            return new Received(0, 1);
        }
        process(stored);
        return new Received(1, 0);
    }

    public int run() {
        var due = repository.claimInbox(clock.instant(), clock.instant().plus(Duration.ofMinutes(2)), properties.batchSize());
        due.forEach(this::process);
        return due.size();
    }

    private void process(PayoutRepository.Inbox stored) {
        try {
            String encoded = cipher.decrypt(stored.encrypted(), PayoutRepository.inboxContext(stored.id()));
            if (!MessageDigest.isEqual(Hashing.sha256(encoded), stored.hash())) {
                throw new IllegalStateException("Payout webhook fingerprint mismatch");
            }
            Event event = json.read(encoded, Event.class);
            payouts.apply(stored.provider(), stored.payoutId(), providers.require(stored.provider()).resolveDestination(event.result()));
            repository.completeInbox(stored.id());
        } catch (RuntimeException exception) {
            repository.retryInbox(stored.id(), clock.instant().plusSeconds(Math.min(3600, 30L << Math.min(stored.attempts(), 6))));
            log.error("Payout webhook {} requires retry: {}", stored.id(), exception.getClass().getSimpleName());
        }
    }
}