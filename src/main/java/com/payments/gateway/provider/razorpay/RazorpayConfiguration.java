package com.payments.gateway.provider.razorpay;

import com.payments.gateway.provider.ProviderHttpProperties;
import com.payments.gateway.shared.config.SecurityProperties;
import com.payments.gateway.shared.json.JsonCodec;
import java.time.Clock;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the Razorpay adapter when {@code pg.providers.razorpay.enabled=true} (ADR-030). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pg.providers.razorpay", name = "enabled", havingValue = "true")
public class RazorpayConfiguration {

    @Bean
    RazorpayPaymentProvider razorpayPaymentProvider(RazorpayProperties properties, ProviderHttpProperties http,
                                                    SecurityProperties security, JsonCodec json, Clock clock) {
        String keyPrefix = "rzp_" + security.apiKeyMode().name().toLowerCase(Locale.ROOT) + "_";
        RazorpayApi api = new RazorpayApi(properties.baseUrl(), http.connectTimeout(), http.readTimeout(), keyPrefix, json);
        return new RazorpayPaymentProvider(properties, api, clock);
    }
}
