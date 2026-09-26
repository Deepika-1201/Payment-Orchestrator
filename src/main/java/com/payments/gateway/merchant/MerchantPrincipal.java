package com.payments.gateway.merchant;

/**
 * An authenticated merchant API caller. {@code readLimit} / {@code writeLimit} are the merchant's own rate-limit
 * overrides, or null for the defaults (ADR-020).
 */
public record MerchantPrincipal(String merchantId, String apiKeyId, RateLimit readLimit, RateLimit writeLimit) {

    public static final String ATTRIBUTE = "pg.merchantPrincipal";
}
