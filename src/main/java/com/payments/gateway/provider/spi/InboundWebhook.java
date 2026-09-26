package com.payments.gateway.provider.spi;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Raw inbound webhook. Header names are normalized to lower case. */
public record InboundWebhook(Map<String, String> headers, String body, Instant receivedAt) {

    public InboundWebhook {
        headers = headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue,
                        (first, second) -> first));
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }
}
