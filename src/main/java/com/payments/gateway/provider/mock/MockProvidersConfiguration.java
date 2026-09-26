package com.payments.gateway.provider.mock;

import com.payments.gateway.provider.spi.ProviderCapabilities;
import com.payments.gateway.provider.spi.ProviderCapabilities.MethodSupport;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.UpiFlow;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Two simulated PSPs for local development and tests. Never enabled in production. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pg.providers.mock", name = "enabled", havingValue = "true")
public class MockProvidersConfiguration {

    public static final String MOCK_ALPHA = "MOCK_ALPHA";
    public static final String MOCK_BETA = "MOCK_BETA";

    private static final long UPI_MAX = 10_000_000L;
    private static final long CARD_MAX = 100_000_000L;

    @Bean
    MockPaymentProvider mockAlphaProvider(MockProviderProperties properties, JsonCodec json, Clock clock) {
        requireSecret(properties);
        ProviderCapabilities capabilities = new ProviderCapabilities(Map.of(
                MethodType.UPI, new MethodSupport(EnumSet.allOf(UpiFlow.class), 100, UPI_MAX, false),
                MethodType.CARD, new MethodSupport(Set.of(), 100, CARD_MAX, true),
                MethodType.NETBANKING, new MethodSupport(Set.of(), 100, CARD_MAX, false)),
                Set.of("INR"), true, true, true);
        return new MockPaymentProvider(MOCK_ALPHA, capabilities, properties, json, clock);
    }

    @Bean
    MockPaymentProvider mockBetaProvider(MockProviderProperties properties, JsonCodec json, Clock clock) {
        requireSecret(properties);
        ProviderCapabilities capabilities = new ProviderCapabilities(Map.of(
                MethodType.UPI, new MethodSupport(EnumSet.allOf(UpiFlow.class), 100, UPI_MAX, false),
                MethodType.CARD, new MethodSupport(Set.of(), 100, CARD_MAX, true)),
                Set.of("INR"), true, true, true);
        return new MockPaymentProvider(MOCK_BETA, capabilities, properties, json, clock);
    }

    @Bean
    MockWebhookSender mockWebhookSender(JsonCodec json, Clock clock) {
        return new MockWebhookSender(json, clock);
    }

    private static void requireSecret(MockProviderProperties properties) {
        if (properties.webhookSecret() == null || properties.webhookSecret().isBlank()) {
            throw new IllegalStateException("pg.providers.mock.webhook-secret must be set when mock providers are enabled");
        }
    }
}
