package com.payments.gateway.provider.spi;

import java.util.Map;
import java.util.Optional;

/**
 * The merchant's own account at a PSP (orchestrator model), with decrypted credentials. {@link #toString()} omits
 * credential values so the account can never leak into logs.
 */
public record MerchantAccount(String id, String merchantId, String providerCode, Map<String, String> credentials) {

    public MerchantAccount {
        credentials = Map.copyOf(credentials);
    }

    public Optional<String> credential(String name) {
        return Optional.ofNullable(credentials.get(name));
    }

    @Override
    public String toString() {
        return "MerchantAccount[id=" + id + ", merchantId=" + merchantId + ", providerCode=" + providerCode
                + ", credentials=" + credentials.keySet() + "]";
    }
}
