package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.FailureCategory;

public record ProviderFailure(String code, String message, FailureCategory category) {
}
