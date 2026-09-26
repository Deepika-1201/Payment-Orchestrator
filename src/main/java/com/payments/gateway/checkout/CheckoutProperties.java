package com.payments.gateway.checkout;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hosted checkout settings. {@code publicBaseUrl} is the customer-facing origin used in session URLs;
 * {@code resultTtl} is how long after the payment's expiry the page still shows the outcome.
 */
@ConfigurationProperties("pg.checkout")
public record CheckoutProperties(String publicBaseUrl, Duration resultTtl, List<Bank> banks) {

    public record Bank(String code, String name) {
    }

    private static final List<Bank> DEFAULT_BANKS = List.of(
            new Bank("HDFC", "HDFC Bank"),
            new Bank("ICIC", "ICICI Bank"),
            new Bank("SBIN", "State Bank of India"),
            new Bank("UTIB", "Axis Bank"),
            new Bank("KKBK", "Kotak Mahindra Bank"));

    public CheckoutProperties {
        publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank() ? "http://localhost:8080" : publicBaseUrl.strip();
        if (!publicBaseUrl.startsWith("https://") && !publicBaseUrl.startsWith("http://")) {
            throw new IllegalArgumentException("pg.checkout.public-base-url must be an http(s) origin");
        }
        while (publicBaseUrl.endsWith("/")) {
            publicBaseUrl = publicBaseUrl.substring(0, publicBaseUrl.length() - 1);
        }
        resultTtl = resultTtl == null ? Duration.ofHours(1) : resultTtl;
        banks = banks == null || banks.isEmpty() ? DEFAULT_BANKS : List.copyOf(banks);
    }

    public boolean isKnownBank(String code) {
        return banks.stream().anyMatch(bank -> bank.code().equals(code));
    }
}
