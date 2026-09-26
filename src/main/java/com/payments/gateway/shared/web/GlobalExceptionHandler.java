package com.payments.gateway.shared.web;

import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Renders every error as RFC 9457 problem details with a stable {@code code} and the {@code request_id}. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Object> handleGateway(GatewayException ex) {
        ProblemDetail problem = problem(ex.code(), ex.getMessage());
        if (!ex.fieldErrors().isEmpty()) {
            problem.setProperty("errors", ex.fieldErrors());
        }
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return respond(ex.code(), headers, problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return respond(ErrorCode.INTERNAL_ERROR, new HttpHeaders(),
                problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<GatewayException.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new GatewayException.FieldError(toSnakeCase(fe.getField()),
                        fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(ErrorCode.VALIDATION_ERROR, "Request validation failed");
        problem.setProperty("errors", errors);
        return respond(ErrorCode.VALIDATION_ERROR, new HttpHeaders(), problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiSecurityFilter.PayloadTooLargeException tooLarge) {
                return handlePayloadTooLarge(tooLarge);
            }
        }
        return respond(ErrorCode.MALFORMED_REQUEST, new HttpHeaders(),
                problem(ErrorCode.MALFORMED_REQUEST, "Request body is missing or is not valid JSON for this endpoint"));
    }

    @ExceptionHandler(ApiSecurityFilter.PayloadTooLargeException.class)
    public ResponseEntity<Object> handlePayloadTooLarge(ApiSecurityFilter.PayloadTooLargeException ex) {
        return respond(ErrorCode.PAYLOAD_TOO_LARGE, new HttpHeaders(), problem(ErrorCode.PAYLOAD_TOO_LARGE, ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing
                && IDEMPOTENCY_HEADER.equalsIgnoreCase(missing.getHeaderName())) {
            return respond(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, new HttpHeaders(),
                    problem(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Mutating requests require an Idempotency-Key header"));
        }
        return respond(ErrorCode.VALIDATION_ERROR, new HttpHeaders(),
                problem(ErrorCode.VALIDATION_ERROR, "Missing or invalid request header or parameter"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("code", codeFor(statusCode).code());
            String requestId = Mdc.requestId();
            if (requestId != null) {
                problem.setProperty("request_id", requestId);
            }
        }
        return response;
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_ERROR;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406, 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }

    private static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.httpStatus()), detail);
        problem.setTitle(code.title());
        problem.setProperty("code", code.code());
        String requestId = Mdc.requestId();
        if (requestId != null) {
            problem.setProperty("request_id", requestId);
        }
        return problem;
    }

    private static ResponseEntity<Object> respond(ErrorCode code, HttpHeaders headers, ProblemDetail problem) {
        return ResponseEntity.status(code.httpStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    static String toSnakeCase(String field) {
        StringBuilder out = new StringBuilder(field.length() + 8);
        for (char c : field.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
