package com.payments.gateway.provider.spi;

public class WebhookVerificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WebhookVerificationException(String message) {
        super(message);
    }
}
