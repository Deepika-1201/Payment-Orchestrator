package com.payments.gateway.provider.spi;

import java.util.Locale;
import java.util.Objects;

/**
 * The PSP's answer to a dispute response (ADR-039). {@code ACCEPTED}: the PSP took it, and {@code dispute} carries the
 * status it reports. {@code REFUSED}: it did not; {@code dispute} is set when the refusal was because the dispute is no
 * longer open, with its current status.
 */
public record ProviderDisputeResponse(Outcome outcome, ProviderDisputeResult dispute, String failureCode,
                                      String failureMessage) {

    public enum Outcome {
        ACCEPTED,
        REFUSED
    }

    public ProviderDisputeResponse {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.ACCEPTED) {
            Objects.requireNonNull(dispute, "dispute");
        } else {
            Objects.requireNonNull(failureCode, "failureCode");
        }
    }

    public static ProviderDisputeResponse accepted(ProviderDisputeResult dispute) {
        return new ProviderDisputeResponse(Outcome.ACCEPTED, dispute, null, null);
    }

    public static ProviderDisputeResponse refused(String failureCode, String failureMessage) {
        return new ProviderDisputeResponse(Outcome.REFUSED, null, failureCode, failureMessage);
    }

    /** Refused because the dispute has moved on; {@code current} is what the PSP reports now. */
    public static ProviderDisputeResponse notOpen(ProviderDisputeResult current) {
        return new ProviderDisputeResponse(Outcome.REFUSED, current, "dispute_not_open",
                "The dispute is " + current.status().name().toLowerCase(Locale.ROOT) + " at the PSP");
    }
}
