package com.payments.gateway.shared.config;

import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.net.UrlSafetyValidator;
import com.payments.gateway.shared.web.ApiSecurityFilter;
import com.payments.gateway.shared.web.RequestIdFilter;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class CoreConfiguration {

    /** Microsecond ticks match PostgreSQL timestamptz; Linux clocks are nanosecond-precise and would not round-trip. */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(10);
        return template;
    }

    /** The legacy single key reads version-1 ciphertexts; the ring's primary key encrypts (ADR-025). */
    @Bean
    SecretCipher secretCipher(SecurityProperties properties) {
        Map<String, byte[]> ring = new LinkedHashMap<>();
        if (properties.dataEncryptionKey() != null && !properties.dataEncryptionKey().isBlank()) {
            ring.put(SecretCipher.LEGACY_KEY_ID, Base64.getDecoder().decode(properties.dataEncryptionKey().trim()));
        }
        for (SecurityProperties.DataKey key : properties.dataEncryptionKeys()) {
            ring.put(key.id(), Base64.getDecoder().decode(key.key().trim()));
        }
        if (ring.isEmpty()) {
            throw new IllegalStateException("pg.security.data-encryption-key or data-encryption-keys must be configured");
        }
        String primary = properties.primaryDataKeyId();
        if (primary == null || primary.isBlank()) {
            if (ring.size() > 1) {
                throw new IllegalStateException("pg.security.primary-data-key-id must name the key that encrypts new data");
            }
            primary = ring.keySet().iterator().next();
        }
        return new SecretCipher(ring, primary);
    }

    @Bean
    UrlSafetyValidator urlSafetyValidator(OutboundWebhookProperties properties) {
        return new UrlSafetyValidator(properties.requireHttps(), properties.allowPrivateTargets());
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** Before authentication, so oversized bodies are refused without any lookup. */
    @Bean
    FilterRegistrationBean<ApiSecurityFilter> apiSecurityFilter(JsonCodec json,
            @Value("${pg.api.max-request-body:256KB}") DataSize maxRequestBody) {
        FilterRegistrationBean<ApiSecurityFilter> registration =
                new FilterRegistrationBean<>(new ApiSecurityFilter(maxRequestBody.toBytes(), json));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
        registration.addUrlPatterns("/v1/*", "/admin/*");
        return registration;
    }
}
