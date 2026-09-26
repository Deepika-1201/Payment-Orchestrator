package com.payments.gateway.payment.domain;

public enum TransitionSource {
    API,
    PROVIDER_RESPONSE,
    PROVIDER_WEBHOOK,
    STATUS_CHECK,
    RECONCILIATION,
    SYSTEM;

    /** Evidence that comes from the PSP itself; only such evidence may overturn a FAILED outcome. */
    public boolean isProviderEvidence() {
        return this == PROVIDER_RESPONSE || this == PROVIDER_WEBHOOK || this == STATUS_CHECK || this == RECONCILIATION;
    }
}
