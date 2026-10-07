package com.payments.gateway.provider.mock;

import com.payments.gateway.provider.spi.ProviderCapabilities;
import com.payments.gateway.provider.spi.ProviderCapabilities.MandateSupport;
import com.payments.gateway.provider.spi.ProviderCapabilities.MethodSupport;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.MandateInstrument;
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
    /** LLD §20.2 (assumed ranges): wallets and pay later up to ₹1,00,000; EMI from ₹3,000; cardless EMI up to ₹5,00,000. */
    private static final long EMI_MIN = 300_000L;
    private static final long CARDLESS_EMI_MAX = 50_000_000L;
    private static final Set<Integer> EMI_TENURES = Set.of(3, 6, 9, 12, 18, 24);
    /** LLD §18.8: ₹1 authorization charge for UPI and card, none for eNACH; eNACH debits up to ₹1 crore. */
    private static final Map<MandateInstrument, MandateSupport> MANDATES = Map.of(
            MandateInstrument.UPI_AUTOPAY, new MandateSupport(100, UPI_MAX),
            MandateInstrument.CARD, new MandateSupport(100, UPI_MAX),
            MandateInstrument.ENACH, new MandateSupport(0, 1_000_000_000L));

    @Bean
    MockPaymentProvider mockAlphaProvider(MockProviderProperties properties, JsonCodec json, Clock clock) {
        requireSecret(properties);
        ProviderCapabilities capabilities = new ProviderCapabilities(Map.of(
                MethodType.UPI, new MethodSupport(EnumSet.allOf(UpiFlow.class), 100, UPI_MAX, false),
                MethodType.CARD, new MethodSupport(Set.of(), 100, CARD_MAX, true, true),
                MethodType.NETBANKING, new MethodSupport(Set.of(), 100, CARD_MAX, false),
                MethodType.WALLET, MethodSupport.ofProviders(Set.of("phonepe", "amazonpay", "mobikwik", "payzapp"), 100, UPI_MAX),
                MethodType.EMI, MethodSupport.emi(EMI_TENURES, EMI_MIN, CARD_MAX),
                MethodType.CARDLESS_EMI, MethodSupport.ofProviders(Set.of("zestmoney", "earlysalary", "hdfc"), EMI_MIN,
                        CARDLESS_EMI_MAX),
                MethodType.PAY_LATER, MethodSupport.ofProviders(Set.of("lazypay", "simpl"), 100, UPI_MAX)),
                Set.of("INR"), true, true, true).withMandates(MANDATES);
        return new MockPaymentProvider(MOCK_ALPHA, capabilities, properties, json, clock);
    }

    @Bean
    MockPaymentProvider mockBetaProvider(MockProviderProperties properties, JsonCodec json, Clock clock) {
        requireSecret(properties);
        ProviderCapabilities capabilities = new ProviderCapabilities(Map.of(
                MethodType.UPI, new MethodSupport(EnumSet.allOf(UpiFlow.class), 100, UPI_MAX, false),
                MethodType.CARD, new MethodSupport(Set.of(), 100, CARD_MAX, true),
                MethodType.WALLET, MethodSupport.ofProviders(Set.of("phonepe", "paytm"), 100, UPI_MAX)),
                Set.of("INR"), true, true, true).withMandates(MANDATES);
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
