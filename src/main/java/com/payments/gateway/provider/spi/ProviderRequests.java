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

    /**
     * {@code providerRefundReference} is null when the refund call timed out; PSPs that list refunds per payment find it
     * by our {@code refundId} under {@code paymentProviderReference}, PSPs that key refunds by order under
     * {@code attemptId}.
     */
    public record RefundStatusQuery(String refundId, String providerRefundReference, String paymentProviderReference,
                                    String attemptId) {
    }

    /** {@code merchantId} selects the merchant's PSP account (orchestrator mode); window is [from, to). */
    public record SettlementReportQuery(String merchantId, java.time.Instant from, java.time.Instant to) {
    }
}
