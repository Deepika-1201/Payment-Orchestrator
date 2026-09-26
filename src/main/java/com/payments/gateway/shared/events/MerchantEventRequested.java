package com.payments.gateway.shared.events;

import java.time.Instant;

/**
 * Published (synchronously, inside the state-change transaction) when a merchant-visible event occurs.
 * The webhook module records it into the outbox tables.
 */
public record MerchantEventRequested(String merchantId, String type, String resourceId, Object payload,
                                     Instant occurredAt) {
}
