package com.payments.gateway.shared.error;

import java.util.Locale;

public enum ErrorCode {
    VALIDATION_ERROR(400, "Request validation failed"),
    MALFORMED_REQUEST(400, "Malformed request"),
    IDEMPOTENCY_KEY_REQUIRED(400, "Idempotency-Key header is required"),
    AUTHENTICATION_REQUIRED(401, "Authentication required"),
    INVALID_API_KEY(401, "Invalid API key"),
    INVALID_SIGNATURE(401, "Invalid signature"),
    RESOURCE_NOT_FOUND(404, "Resource not found"),
    METHOD_NOT_ALLOWED(405, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported media type"),
    PAYMENT_INVALID_STATE(409, "Payment is not in a valid state for this operation"),
    REFUND_ALREADY_EXISTS(409, "A refund with this merchant_refund_id already exists"),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(409, "A request with this Idempotency-Key is still in progress"),
    IDEMPOTENCY_KEY_REUSE(422, "Idempotency-Key was already used with a different request"),
    AMOUNT_EXCEEDS_REFUNDABLE(422, "Refund amount exceeds the refundable amount"),
    CAPTURE_AMOUNT_MISMATCH(422, "Capture amount must equal the authorized amount"),
    UNSUPPORTED_PAYMENT_METHOD(422, "No linked provider supports this payment"),
    UNSUPPORTED_CURRENCY(422, "Currency is not supported"),
    RATE_LIMITED(429, "Too many requests"),
    NO_PROVIDER_AVAILABLE(503, "No payment provider is currently available"),
    INTERNAL_ERROR(500, "Internal error");

    private final int httpStatus;
    private final String title;

    ErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String title() {
        return title;
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isClientError() {
        return httpStatus >= 400 && httpStatus < 500;
    }
}
