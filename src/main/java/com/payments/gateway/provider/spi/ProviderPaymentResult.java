package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.Conversion;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import java.util.Objects;

/**
 * Normalized PSP answer for payment operations. {@code amount} is the PSP-reported amount, when known; {@code card}
 * is the card's network and last 4 digits once the customer has paid by card; {@code conversion} is what the PSP
 * settled in INR for a payment in another currency, when it says (ADR-040).
 */
public record ProviderPaymentResult(Outcome outcome, String providerReference, NextAction nextAction,
                                    ProviderFailure failure, Money amount, String rawStatus, CardDetails card,
                                    Conversion conversion) {

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

    public ProviderPaymentResult(Outcome outcome, String providerReference, NextAction nextAction,
                                 ProviderFailure failure, Money amount, String rawStatus, CardDetails card) {
        this(outcome, providerReference, nextAction, failure, amount, rawStatus, card, null);
    }

    public ProviderPaymentResult(Outcome outcome, String providerReference, NextAction nextAction,
                                 ProviderFailure failure, Money amount, String rawStatus) {
        this(outcome, providerReference, nextAction, failure, amount, rawStatus, null, null);
    }

    public ProviderPaymentResult withCard(CardDetails details) {
        return new ProviderPaymentResult(outcome, providerReference, nextAction, failure, amount, rawStatus, details,
                conversion);
    }

    public ProviderPaymentResult withConversion(Conversion reported) {
        return new ProviderPaymentResult(outcome, providerReference, nextAction, failure, amount, rawStatus, card,
                reported);
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
