package com.payments.gateway.provider.spi;

/** The request definitely did not reach/was not processed by the PSP (connect refused, circuit open). Safe to fail over. */
public class ProviderUnavailableException extends ProviderException {

    private static final long serialVersionUID = 1L;

    public ProviderUnavailableException(String providerCode, String message) {
        super(providerCode, message, null);
    }

    public ProviderUnavailableException(String providerCode, String message, Throwable cause) {
        super(providerCode, message, cause);
    }
}
