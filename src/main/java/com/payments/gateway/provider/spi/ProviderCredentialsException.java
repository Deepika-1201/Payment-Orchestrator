package com.payments.gateway.provider.spi;

/**
 * The PSP rejected the merchant account's credentials, so nothing was processed. Safe to fail over, but it is the
 * merchant's configuration, not the PSP's health: it must not open the PSP-wide circuit or lower its score.
 */
public class ProviderCredentialsException extends ProviderUnavailableException {

    private static final long serialVersionUID = 1L;

    public ProviderCredentialsException(String providerCode, String message) {
        super(providerCode, message);
    }
}
