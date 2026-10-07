package com.payments.gateway.provider.spi;

import java.time.Instant;
import java.util.Objects;

/** Normalized state of a pre-debit notification (ADR-035). {@code deliveredAt} is set once it reached the customer. */
public record ProviderNotificationResult(Status status, String providerReference, Instant deliveredAt,
                                         ProviderFailure failure, String rawStatus) {

    public enum Status {
        PENDING,
        DELIVERED,
        FAILED,
        NOT_FOUND
    }

    public ProviderNotificationResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.DELIVERED) {
            Objects.requireNonNull(deliveredAt, "deliveredAt is required for DELIVERED results");
        }
        if (status == Status.FAILED) {
            Objects.requireNonNull(failure, "failure is required for FAILED results");
        }
    }

    public static ProviderNotificationResult pending(String reference, String raw) {
        return new ProviderNotificationResult(Status.PENDING, reference, null, null, raw);
    }

    public static ProviderNotificationResult delivered(String reference, Instant deliveredAt, String raw) {
        return new ProviderNotificationResult(Status.DELIVERED, reference, deliveredAt, null, raw);
    }

    public static ProviderNotificationResult failed(String reference, ProviderFailure failure, String raw) {
        return new ProviderNotificationResult(Status.FAILED, reference, null, failure, raw);
    }

    public static ProviderNotificationResult notFound() {
        return new ProviderNotificationResult(Status.NOT_FOUND, null, null, null, "not_found");
    }
}
