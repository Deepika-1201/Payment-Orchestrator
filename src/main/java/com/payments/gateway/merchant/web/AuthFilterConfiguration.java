package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.shared.config.SecurityProperties;
import com.payments.gateway.shared.json.JsonCodec;
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
