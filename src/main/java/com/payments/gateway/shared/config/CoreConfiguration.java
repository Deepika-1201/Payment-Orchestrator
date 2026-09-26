package com.payments.gateway.shared.config;

import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.net.UrlSafetyValidator;
import com.payments.gateway.shared.web.RequestIdFilter;
import java.time.Clock;
import java.util.Base64;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class CoreConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(10);
        return template;
    }

    @Bean
    SecretCipher secretCipher(SecurityProperties properties) {
        if (properties.dataEncryptionKey() == null || properties.dataEncryptionKey().isBlank()) {
            throw new IllegalStateException("pg.security.data-encryption-key must be configured (base64, 32 bytes)");
        }
        return new SecretCipher(Base64.getDecoder().decode(properties.dataEncryptionKey()));
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
}
