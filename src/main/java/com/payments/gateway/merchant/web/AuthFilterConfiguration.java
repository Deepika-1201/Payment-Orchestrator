package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.shared.config.SecurityProperties;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.web.AdminRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
        List<AdminAuthFilter.Credential> credentials = new ArrayList<>();
        for (SecurityProperties.AdminUser user : security.adminUsers()) {
            credentials.add(new AdminAuthFilter.Credential(new AdminPrincipal(user.name(), user.roles()), user.tokenSha256()));
        }
        for (int i = 0; i < security.adminTokens().size(); i++) {
            credentials.add(new AdminAuthFilter.Credential(new AdminPrincipal("admin-token-" + i, Set.of(AdminRole.ADMIN)),
                    AdminAuthFilter.sha256Hex(security.adminTokens().get(i))));
        }
        if (credentials.isEmpty()) {
            log.warn("No pg.security.admin-users or admin-tokens configured: admin API is disabled");
        } else if (!security.adminTokens().isEmpty()) {
            log.warn("{} plaintext pg.security.admin-tokens configured with the ADMIN role; use admin-users with roles "
                    + "outside local development (ADR-019)", security.adminTokens().size());
        }
        FilterRegistrationBean<AdminAuthFilter> registration =
                new FilterRegistrationBean<>(new AdminAuthFilter(credentials, json));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    WebMvcConfigurer adminPermissions(JsonCodec json, MeterRegistry meters) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new AdminPermissionInterceptor(json, meters)).addPathPatterns("/admin/**");
            }
        };
    }
}
