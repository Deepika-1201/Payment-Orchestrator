package com.payments.gateway.payment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** Request bodies of the mandate API (LLD §18.2). Enum-like values are validated here and parsed in the web layer. */
public final class MandateRequests {

    private MandateRequests() {
    }

    public record CreateMandate(
            @NotBlank @Pattern(regexp = "(?i)upi_autopay|card|enach", message = "must be upi_autopay, card or enach") String instrument,
            @NotNull @Min(100) @Max(1_000_000_000L) Long maxAmount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an upper-case ISO 4217 code") String currency,
            @NotBlank @Pattern(regexp = "(?i)daily|weekly|fortnightly|monthly|bimonthly|quarterly|half_yearly|yearly|as_presented",
                    message = "must be daily, weekly, fortnightly, monthly, bimonthly, quarterly, half_yearly, yearly or as_presented")
            String frequency,
            Instant startAt,
            Instant endAt,
            @Size(max = 255) String description,
            @NotNull @Valid Customer customer,
            @Size(max = 20) Map<@Size(min = 1, max = 40) String, @Size(max = 500) String> metadata,
            @Size(max = 2048) @Pattern(regexp = "https?://\\S+", message = "must be an http(s) URL") String returnUrl) {

        public CreateMandate {
            metadata = metadata == null ? null : new TreeMap<>(metadata);
        }
    }

    public record Customer(
            @Size(max = 64) String reference,
            @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}", message = "must be 8-15 digits, optionally prefixed with +") String phone) {
    }

    public record CreateDebit(
            @NotNull @Min(100) @Max(1_000_000_000L) Long amount,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_\\-:.]+", message = "may contain letters, digits, _ - : .") String merchantDebitId,
            Instant dueAt,
            @Size(max = 255) String description) {
    }
}
