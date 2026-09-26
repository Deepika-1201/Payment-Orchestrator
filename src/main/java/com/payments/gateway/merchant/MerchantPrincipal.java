package com.payments.gateway.merchant;

/** Authenticated merchant for the current request (set by {@code ApiKeyAuthFilter}). */
public record MerchantPrincipal(String merchantId, String apiKeyId) {

    public static final String ATTRIBUTE = "pg.merchantPrincipal";
}
