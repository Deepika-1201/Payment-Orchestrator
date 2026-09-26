package com.payments.gateway.checkout.web;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CheckoutWebConfiguration {

    /** Set on every /checkout response, including errors: the URL carries a bearer token, so never leak or cache it. */
    @Bean
    FilterRegistrationBean<Filter> checkoutSecurityHeaders() {
        Filter filter = (request, response, chain) -> {
            HttpServletResponse http = (HttpServletResponse) response;
            http.setHeader("Content-Security-Policy", CheckoutPages.CONTENT_SECURITY_POLICY);
            http.setHeader("X-Frame-Options", "DENY");
            http.setHeader("X-Content-Type-Options", "nosniff");
            http.setHeader("Referrer-Policy", "no-referrer");
            http.setHeader("Cache-Control", "no-store");
            http.setHeader("X-Robots-Tag", "noindex, nofollow");
            chain.doFilter(request, response);
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("checkoutSecurityHeaders");
        registration.addUrlPatterns("/checkout/*");
        return registration;
    }
}
