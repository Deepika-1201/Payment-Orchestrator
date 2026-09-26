package com.payments.gateway.shared.model;

import java.time.Instant;
import java.util.Objects;

/** What the customer must do next to complete an attempt. */
public record NextAction(Type type, String url, String upiUri, String qrPayload, Instant expiresAt) {

    public enum Type {
        REDIRECT,
        UPI_INTENT,
        DISPLAY_QR,
        AWAIT_APPROVAL
    }

    public NextAction {
        Objects.requireNonNull(type, "type");
    }

    public static NextAction redirect(String url) {
        return new NextAction(Type.REDIRECT, url, null, null, null);
    }

    public static NextAction upiIntent(String uri) {
        return new NextAction(Type.UPI_INTENT, null, uri, null, null);
    }

    public static NextAction displayQr(String payload, Instant expiresAt) {
        return new NextAction(Type.DISPLAY_QR, null, null, payload, expiresAt);
    }

    public static NextAction awaitApproval() {
        return new NextAction(Type.AWAIT_APPROVAL, null, null, null, null);
    }
}
