package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.Money;
import java.util.Objects;

public record ProviderRefundResult(Outcome outcome, String providerReference, ProviderFailure failure, Money amount,
                                   String rawStatus) {

    public enum Outcome {
        PENDING,
        SUCCEEDED,
        FAILED,
        NOT_FOUND
    }

    public ProviderRefundResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.FAILED) {
            Objects.requireNonNull(failure, "failure is required for FAILED results");
        }
    }

    public static ProviderRefundResult pending(String reference, Money amount) {
        return new ProviderRefundResult(Outcome.PENDING, reference, null, amount, "pending");
    }

    public static ProviderRefundResult succeeded(String reference, Money amount) {
        return new ProviderRefundResult(Outcome.SUCCEEDED, reference, null, amount, "succeeded");
    }

    public static ProviderRefundResult failed(String reference, ProviderFailure failure) {
        return new ProviderRefundResult(Outcome.FAILED, reference, failure, null, "failed");
    }

    public static ProviderRefundResult notFound() {
        return new ProviderRefundResult(Outcome.NOT_FOUND, null, null, null, "not_found");
    }
}
