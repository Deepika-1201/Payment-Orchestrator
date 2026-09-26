package com.payments.gateway.shared.error;

import java.util.List;
import java.util.Objects;

/** Business or request error with a stable machine-readable code, rendered as RFC 9457 problem details. */
public class GatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient List<FieldError> fieldErrors;
    private final Integer retryAfterSeconds;

    public GatewayException(ErrorCode code, String detail) {
        this(code, detail, List.of(), null);
    }

    public GatewayException(ErrorCode code, String detail, List<FieldError> fieldErrors, Integer retryAfterSeconds) {
        super(detail);
        this.code = Objects.requireNonNull(code, "code");
        this.fieldErrors = List.copyOf(fieldErrors);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static GatewayException notFound(String what, String id) {
        return new GatewayException(ErrorCode.RESOURCE_NOT_FOUND, what + " " + id + " not found");
    }

    public static GatewayException invalidState(String detail) {
        return new GatewayException(ErrorCode.PAYMENT_INVALID_STATE, detail);
    }

    public static GatewayException validation(String field, String message) {
        return new GatewayException(ErrorCode.VALIDATION_ERROR, field + " " + message,
                List.of(new FieldError(field, message)), null);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public record FieldError(String field, String message) {
    }
}
