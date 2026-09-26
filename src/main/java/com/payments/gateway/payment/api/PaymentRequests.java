package com.payments.gateway.payment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.TreeMap;

/** Request bodies of the payment API. Enum-like values are validated here and parsed in the web layer. */
public final class PaymentRequests {

    private PaymentRequests() {
    }

    public record CreatePayment(
            @NotNull @Min(100) @Max(100_000_000) Long amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an upper-case ISO 4217 code") String currency,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_\\-:.]+", message = "may contain letters, digits, _ - : .") String merchantOrderId,
            @Pattern(regexp = "(?i)automatic|manual", message = "must be automatic or manual") String captureMethod,
            @Size(max = 255) String description,
            @Valid Customer customer,
            @Size(max = 20) Map<@Size(min = 1, max = 40) String, @Size(max = 500) String> metadata,
            @Min(60) @Max(86400) Integer expiresInSeconds) {

        public CreatePayment {
            metadata = metadata == null ? null : new TreeMap<>(metadata);
        }
    }

    public record Customer(
            @Size(max = 64) String reference,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "\\+?[0-9]{8,15}", message = "must be 8-15 digits, optionally prefixed with +") String phone) {
    }

    public record ConfirmPayment(
            @NotNull @Valid PaymentMethod paymentMethod,
            @Size(max = 2048) @Pattern(regexp = "https?://\\S+", message = "must be an http(s) URL") String returnUrl,
            @Valid Client client) {
    }

    public record PaymentMethod(
            @NotBlank @Pattern(regexp = "(?i)upi|card|netbanking", message = "must be upi, card or netbanking") String type,
            @Valid Upi upi,
            @Valid Netbanking netbanking) {
    }

    public record Upi(
            @NotBlank @Pattern(regexp = "(?i)intent|qr|collect", message = "must be intent, qr or collect") String flow,
            @Size(max = 255) @Pattern(regexp = "[A-Za-z0-9._\\-]{2,200}@[A-Za-z][A-Za-z0-9]{1,50}", message = "must be a valid VPA") String vpa) {
    }

    public record Netbanking(
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,16}", message = "must be an upper-case bank code") String bankCode) {
    }

    public record Client(@Size(max = 45) String ip, @Size(max = 512) String userAgent, @Size(max = 128) String deviceId) {
    }

    public record CapturePayment(@Min(1) Long amount) {
    }

    public record CancelPayment(
            @Pattern(regexp = "requested_by_customer|abandoned|duplicate|fraudulent|other",
                    message = "must be requested_by_customer, abandoned, duplicate, fraudulent or other") String reason) {
    }

    public record CreateRefund(
            @Min(1) Long amount,
            @Size(max = 255) String reason,
            @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_\\-:.]+", message = "may contain letters, digits, _ - : .") String merchantRefundId) {
    }
}
