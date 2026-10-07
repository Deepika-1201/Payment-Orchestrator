package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.NextAction;
import java.util.Objects;

/**
 * Normalized PSP answer about a mandate. {@code providerReference} identifies the registration (e.g. a registration
 * link), {@code providerMandateReference} the authorized mandate (token, UMN or UMRN) once known, and
 * {@code registrationPaymentReference} the PSP's reference of the authorization charge, if any.
 */
public record ProviderMandateResult(Status status, String providerReference, String providerMandateReference,
                                    String providerCustomerReference, String registrationPaymentReference,
                                    NextAction nextAction, ProviderFailure failure, String rawStatus) {

    public enum Status {
        PENDING,
        ACTIVE,
        PAUSED,
        REVOKED,
        EXPIRED,
        FAILED,
        NOT_FOUND
    }

    public ProviderMandateResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.FAILED) {
            Objects.requireNonNull(failure, "failure is required for FAILED results");
        }
    }

    public static ProviderMandateResult of(Status status, String providerReference, String providerMandateReference,
                                           String rawStatus) {
        return new ProviderMandateResult(status, providerReference, providerMandateReference, null, null, null, null,
                rawStatus);
    }

    public static ProviderMandateResult failed(String providerReference, ProviderFailure failure, String rawStatus) {
        return new ProviderMandateResult(Status.FAILED, providerReference, null, null, null, null, failure, rawStatus);
    }

    public static ProviderMandateResult notFound() {
        return new ProviderMandateResult(Status.NOT_FOUND, null, null, null, null, null, null, "not_found");
    }
}
