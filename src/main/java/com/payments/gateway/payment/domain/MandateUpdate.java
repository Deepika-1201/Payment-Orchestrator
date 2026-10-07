package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.NextAction;

/**
 * PSP evidence about a mandate. {@code status} is null when the PSP has nothing new (e.g. a registration it never
 * received); references are only ever filled in, never replaced.
 */
public record MandateUpdate(MandateStatus status, String providerReference, String providerMandateReference,
                            String providerCustomerReference, NextAction nextAction, Failure failure) {

    public static MandateUpdate of(MandateStatus status) {
        return new MandateUpdate(status, null, null, null, null, null);
    }

    public static MandateUpdate failed(Failure failure) {
        return new MandateUpdate(MandateStatus.FAILED, null, null, null, null, failure);
    }
}
