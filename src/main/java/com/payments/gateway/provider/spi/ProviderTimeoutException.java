package com.payments.gateway.provider.spi;

/** The PSP may or may not have processed the request (read timeout, ambiguous error). Never fail over. */
public class ProviderTimeoutException extends ProviderException {

    private static final long serialVersionUID = 1L;

    public ProviderTimeoutException(String providerCode, String message) {
        super(providerCode, message, null);
    }

    public ProviderTimeoutException(String providerCode, String message, Throwable cause) {
        super(providerCode, message, cause);
    }
}
