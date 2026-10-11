package com.payments.gateway.payout;

import com.payments.gateway.payout.spi.PayoutProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PayoutProviders {
    private final List<PayoutProvider> providers;

    public PayoutProviders(List<PayoutProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public PayoutProvider require(String code) {
        return find(code)
                .orElseThrow(() -> new IllegalStateException("Payout provider is not configured: " + code));
    }

    public Optional<PayoutProvider> find(String code) {
        return providers.stream().filter(provider -> provider.code().equals(code)).findFirst();
    }
}