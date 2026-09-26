package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import java.util.Objects;

/** Normalized PSP answer for payment operations. {@code amount} is the PSP-reported amount, when known. */
public record ProviderPaymentResult(Outcome outcome, String providerReference, NextAction nextAction,
                                    ProviderFailure failure, Money amount, String rawStatus) {

    public enum Outcome {
        REQUIRES_ACTION,
        PENDING,
        AUTHORIZED,
        SUCCEEDED,
        FAILED,
        VOIDED,
        NOT_FOUND
    }

    public ProviderPaymentResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.FAILED) {
            Objects.requireNonNull(failure, "failure is required for FAILED results");
        }
    }

    public static ProviderPaymentResult requiresAction(String reference, NextAction nextAction, String raw) {
        return new ProviderPaymentResult(Outcome.REQUIRES_ACTION, reference, nextAction, null, null, raw);
    }

    public static ProviderPaymentResult pending(String reference, String raw) {
        return new ProviderPaymentResult(Outcome.PENDING, reference, null, null, null, raw);
    }

    public static ProviderPaymentResult authorized(String reference, Money amount, String raw) {
        return new ProviderPaymentResult(Outcome.AUTHORIZED, reference, null, null, amount, raw);
    }

    public static ProviderPaymentResult succeeded(String reference, Money amount, String raw) {
        return new ProviderPaymentResult(Outcome.SUCCEEDED, reference, null, null, amount, raw);
    }

    public static ProviderPaymentResult failed(String reference, ProviderFailure failure, String raw) {
        return new ProviderPaymentResult(Outcome.FAILED, reference, null, failure, null, raw);
    }

    public static ProviderPaymentResult voided(String reference, String raw) {
        return new ProviderPaymentResult(Outcome.VOIDED, reference, null, null, null, raw);
    }

    public static ProviderPaymentResult notFound() {
        return new ProviderPaymentResult(Outcome.NOT_FOUND, null, null, null, null, "not_found");
    }
}
