package com.payments.gateway.provider.spi;

/** The PSP understood the request and refused it: retrying it unchanged will not help. */
public class ProviderRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String providerCode;
    private final String failureCode;

    public ProviderRefusedException(String providerCode, String failureCode, String message) {
        super(message);
        this.providerCode = providerCode;
        this.failureCode = failureCode;
    }

    public String providerCode() {
        return providerCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
