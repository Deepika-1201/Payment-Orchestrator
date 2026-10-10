package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.Conversion;
import com.payments.gateway.shared.model.Money;
import java.util.Objects;

/** {@code conversion}: what the PSP settled in INR for a refund in another currency, when it says (ADR-040). */
public record ProviderRefundResult(Outcome outcome, String providerReference, ProviderFailure failure, Money amount,
                                   String rawStatus, Conversion conversion) {

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

    public ProviderRefundResult(Outcome outcome, String providerReference, ProviderFailure failure, Money amount,
                                String rawStatus) {
        this(outcome, providerReference, failure, amount, rawStatus, null);
    }

    public ProviderRefundResult withConversion(Conversion reported) {
        return new ProviderRefundResult(outcome, providerReference, failure, amount, rawStatus, reported);
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
