package com.payments.gateway.provider.spi;

/** A credential an adapter needs from the merchant's PSP account. Secret values are never shown after saving. */
public record CredentialField(String name, boolean secret, boolean required) {
}
