package com.payments.gateway.provider;

import com.payments.gateway.provider.spi.PaymentProvider;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class ProviderRegistry {

    private final Map<String, PaymentProvider> providers;

    public ProviderRegistry(ObjectProvider<PaymentProvider> providers) {
        Map<String, PaymentProvider> byCode = new LinkedHashMap<>();
        providers.orderedStream().forEach(provider -> {
            if (byCode.putIfAbsent(provider.code(), provider) != null) {
                throw new IllegalStateException("Duplicate payment provider code: " + provider.code());
            }
        });
        this.providers = Collections.unmodifiableMap(byCode);
    }

    public Optional<PaymentProvider> find(String code) {
        return Optional.ofNullable(providers.get(code));
    }

    public PaymentProvider require(String code) {
        PaymentProvider provider = providers.get(code);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown payment provider: " + code);
        }
        return provider;
    }

    public boolean exists(String code) {
        return providers.containsKey(code);
    }

    public Collection<PaymentProvider> all() {
        return providers.values();
    }
}
