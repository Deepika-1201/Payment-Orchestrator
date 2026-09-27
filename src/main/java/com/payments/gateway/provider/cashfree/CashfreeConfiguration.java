package com.payments.gateway.provider.cashfree;

import com.payments.gateway.provider.ProviderHttpProperties;
import com.payments.gateway.shared.config.SecurityProperties;
import com.payments.gateway.shared.json.JsonCodec;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the Cashfree adapter when {@code pg.providers.cashfree.enabled=true} (ADR-031). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pg.providers.cashfree", name = "enabled", havingValue = "true")
public class CashfreeConfiguration {

    @Bean
    CashfreePaymentProvider cashfreePaymentProvider(CashfreeProperties properties, ProviderHttpProperties http,
                                                    SecurityProperties security, JsonCodec json, Clock clock) {
        // Cashfree keys carry no test/live marker, so the deployment's mode picks the environment instead.
        URI baseUrl = properties.baseUrl() != null ? properties.baseUrl()
                : security.apiKeyMode() == SecurityProperties.ApiKeyMode.LIVE ? CashfreeProperties.PRODUCTION
                : CashfreeProperties.SANDBOX;
        CashfreeApi api = new CashfreeApi(baseUrl, http.connectTimeout(), http.readTimeout(), properties.apiVersion(), json);
        return new CashfreePaymentProvider(properties, api, clock);
    }
}
