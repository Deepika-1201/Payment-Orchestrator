package com.payments.gateway.provider.spi;

public abstract class ProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String providerCode;

    protected ProviderException(String providerCode, String message, Throwable cause) {
        super(providerCode + ": " + message, cause);
        this.providerCode = providerCode;
    }

    public String providerCode() {
        return providerCode;
    }
}
