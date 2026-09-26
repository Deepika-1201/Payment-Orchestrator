package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.shared.config.SecurityProperties;
import com.payments.gateway.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class AuthFilterConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthFilterConfiguration.class);

    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(MerchantDirectory merchants, JsonCodec json) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(new ApiKeyAuthFilter(merchants, json));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    /** Runs after {@link ApiKeyAuthFilter}, so budgets are per authenticated merchant. */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimitProperties properties, JsonCodec json, Clock clock,
                                                            MeterRegistry meters) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(properties, json, clock, meters));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        registration.setEnabled(properties.enabled());
        return registration;
    }

    @Bean
    FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(SecurityProperties security, JsonCodec json) {
        if (security.adminTokens().isEmpty()) {
            log.warn("No pg.security.admin-tokens configured: admin API is disabled");
        }
        FilterRegistrationBean<AdminAuthFilter> registration =
                new FilterRegistrationBean<>(new AdminAuthFilter(security.adminTokens(), json));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
