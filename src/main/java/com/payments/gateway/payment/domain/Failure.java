package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.FailureCategory;
import java.util.Objects;

public record Failure(String code, FailureCategory category, String message) {

    public Failure {
        code = code == null || code.isBlank() ? "provider_failure" : code;
        Objects.requireNonNull(category, "category");
    }
}
