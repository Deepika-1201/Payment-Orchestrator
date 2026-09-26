package com.payments.gateway.payment.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Response bodies of the payment API; also the payload of merchant webhooks. */
public final class PaymentResponses {

    private PaymentResponses() {
    }

    public record PaymentResponse(
            String id,
            String object,
            String merchantOrderId,
            long amount,
            String currency,
            String status,
            String captureMethod,
            String description,
            CustomerResponse customer,
            Map<String, String> metadata,
            long amountCaptured,
            long amountRefunded,
            NextActionResponse nextAction,
            AttemptResponse latestAttempt,
            int attemptCount,
            ErrorResponse lastError,
            String cancellationReason,
            Instant expiresAt,
            Instant authorizationExpiresAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    public record CustomerResponse(String reference, String email, String phone) {
    }

    public record NextActionResponse(String type, String url, String upiUri, String qrPayload, Instant expiresAt) {
    }

    public record AttemptResponse(String id, int attemptNumber, String status, String method, String upiFlow,
                                  String bankCode, CardResponse card, String provider, String providerReference,
                                  ErrorResponse failure, Instant createdAt) {
    }

    public record CardResponse(String network, String last4) {
    }

    public record ErrorResponse(String code, String category, String message) {
    }

    public record RefundResponse(String id, String object, String paymentId, String attemptId, long amount,
                                 String currency, String status, String reason, String merchantRefundId,
                                 String initiatedBy, String provider, String providerReference, ErrorResponse failure,
                                 Instant createdAt, Instant updatedAt, long version) {
    }

    public record ListResponse<T>(List<T> data) {
    }
}
