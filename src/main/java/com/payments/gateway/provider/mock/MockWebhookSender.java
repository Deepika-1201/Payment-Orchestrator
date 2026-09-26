package com.payments.gateway.provider.mock;

import com.payments.gateway.shared.json.JsonCodec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

/** Delivers signed webhooks from a simulated PSP to the gateway's inbound webhook endpoint. */
public class MockWebhookSender {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final JsonCodec json;
    private final Clock clock;

    public MockWebhookSender(JsonCodec json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    /** Posts to the transaction's merchant account endpoint, as a PSP configured per merchant account would. */
    public int send(String gatewayBaseUrl, MockPaymentProvider provider, MockPsp.Txn txn, MockWebhookPayload payload) {
        String body = json.write(payload);
        String path = "/v1/webhooks/providers/" + provider.code() + (txn.accountId() == null ? "" : "/" + txn.accountId());
        HttpRequest request = HttpRequest.newBuilder(URI.create(gatewayBaseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header(MockPaymentProvider.SIGNATURE_HEADER, provider.sign(txn, clock.instant().getEpochSecond(), body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
