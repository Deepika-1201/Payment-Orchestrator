package com.payments.gateway.payout.razorpay;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.payouts.razorpay")
public record RazorpayPayoutProperties(boolean enabled, URI baseUrl, String keyId, String keySecret, String webhookSecret,
                                      String accountId, String sourceAccount, Duration connectTimeout, Duration readTimeout) {
    public RazorpayPayoutProperties {
        baseUrl = baseUrl == null ? URI.create("https://api.razorpay.com/v1") : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        if (!baseUrl.isAbsolute() || baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid RazorpayX endpoint or timeout");
        }
        if (enabled && (blank(keyId) || blank(keySecret) || blank(webhookSecret) || blank(accountId) || blank(sourceAccount))) {
            throw new IllegalArgumentException("RazorpayX requires private credentials, webhook account id and source escrow account");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "RazorpayPayoutProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", credentials=redacted]";
    }
}