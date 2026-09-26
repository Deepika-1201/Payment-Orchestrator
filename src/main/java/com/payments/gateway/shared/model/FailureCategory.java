package com.payments.gateway.shared.model;

public enum FailureCategory {
    CUSTOMER,
    ISSUER,
    PROVIDER,
    PROVIDER_UNAVAILABLE,
    VALIDATION,
    RISK,
    TIMEOUT,
    NOT_SUBMITTED;

    /** Only provider-attributable failures lower a provider's routing score. */
    public boolean countsAgainstProvider() {
        return this == PROVIDER || this == PROVIDER_UNAVAILABLE || this == TIMEOUT;
    }
}
