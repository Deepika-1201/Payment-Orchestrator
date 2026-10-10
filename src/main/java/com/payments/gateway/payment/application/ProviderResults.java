package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.Failure;
import com.payments.gateway.payment.domain.RefundStatus;
import com.payments.gateway.provider.spi.ProviderFailure;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.shared.model.FailureCategory;

/** Translates normalized PSP results into domain updates. */
final class ProviderResults {

    private ProviderResults() {
    }

    static AttemptUpdate toUpdate(ProviderPaymentResult result) {
        AttemptStatus status = switch (result.outcome()) {
            case REQUIRES_ACTION -> AttemptStatus.REQUIRES_ACTION;
            case PENDING -> AttemptStatus.PENDING;
            case AUTHORIZED -> AttemptStatus.AUTHORIZED;
            case SUCCEEDED -> AttemptStatus.SUCCEEDED;
            case FAILED -> AttemptStatus.FAILED;
            case VOIDED -> AttemptStatus.VOIDED;
            case NOT_FOUND -> throw new IllegalArgumentException("NOT_FOUND must be handled by the caller");
        };
        return new AttemptUpdate(status, result.providerReference(), result.nextAction(),
            result.failure() == null ? null : toFailure(result.failure()), result.amount(), result.card(), result.conversion());
    }

    static Failure toFailure(ProviderFailure failure) {
        return new Failure(failure.code(), failure.category() == null ? FailureCategory.PROVIDER : failure.category(),
                failure.message());
    }

    static RefundStatus toRefundStatus(ProviderRefundResult result) {
        return switch (result.outcome()) {
            case PENDING -> RefundStatus.PENDING;
            case SUCCEEDED -> RefundStatus.SUCCEEDED;
            case FAILED -> RefundStatus.FAILED;
            case NOT_FOUND -> throw new IllegalArgumentException("NOT_FOUND must be handled by the caller");
        };
    }
}
