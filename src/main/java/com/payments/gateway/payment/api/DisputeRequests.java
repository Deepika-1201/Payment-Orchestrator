package com.payments.gateway.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Merchant requests answering a dispute (LLD §22.1). */
public final class DisputeRequests {

    private DisputeRequests() {
    }

    /** One evidence file, its content base64-encoded; the type is checked against the file's first bytes. */
    public record EvidenceFile(
            @NotBlank @Pattern(regexp = "shipping_proof|billing_proof|cancellation_proof|customer_communication"
                    + "|proof_of_service|explanation_letter|refund_confirmation|access_activity_log"
                    + "|refund_cancellation_policy|terms_and_conditions|other",
                    message = "must be an evidence category") String category,
            @NotBlank @Size(max = 255) @Pattern(regexp = "[^/\\\\\\p{Cntrl}]+",
                    message = "must not contain path separators or control characters") String fileName,
            @NotBlank @Pattern(regexp = "application/pdf|image/jpeg|image/png",
                    message = "must be application/pdf, image/jpeg or image/png") String contentType,
            @NotBlank String contentBase64) {
    }

    public record Contest(
            @NotBlank @Size(max = 1000) String statement,
            @NotNull @Size(min = 1, max = 10) List<@NotBlank String> evidenceFileIds) {
    }
}
