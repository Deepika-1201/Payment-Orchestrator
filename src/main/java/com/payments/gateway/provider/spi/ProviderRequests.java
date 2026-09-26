package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.Money;

public final class ProviderRequests {

    private ProviderRequests() {
    }

    public record PaymentStatusQuery(String attemptId, String providerReference) {
    }

    public record CaptureRequest(String attemptId, String providerReference, Money amount) {
    }

    public record VoidRequest(String attemptId, String providerReference) {
    }

    /** {@code refundId} is sent to the PSP as the refund idempotency key. */
    public record RefundRequest(String refundId, String attemptId, String paymentProviderReference, Money amount,
                                String reason) {
    }

    public record RefundStatusQuery(String refundId, String providerRefundReference) {
    }
}
