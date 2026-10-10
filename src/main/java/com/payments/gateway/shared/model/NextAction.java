package com.payments.gateway.shared.model;

import java.time.Instant;
import java.util.Objects;

/** What the customer must do next to complete an attempt; {@code bankTransfer} for {@code BANK_TRANSFER} (ADR-038). */
public record NextAction(Type type, String url, String upiUri, String qrPayload, Instant expiresAt,
                         BankTransferDetails bankTransfer) {

    public enum Type {
        REDIRECT,
        UPI_INTENT,
        DISPLAY_QR,
        AWAIT_APPROVAL,
        BANK_TRANSFER
    }

    public NextAction {
        Objects.requireNonNull(type, "type");
        if ((type == Type.BANK_TRANSFER) != (bankTransfer != null)) {
            throw new IllegalArgumentException("bank transfer details go with type BANK_TRANSFER only");
        }
    }

    public NextAction(Type type, String url, String upiUri, String qrPayload, Instant expiresAt) {
        this(type, url, upiUri, qrPayload, expiresAt, null);
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

    public static NextAction bankTransfer(BankTransferDetails details, Instant expiresAt) {
        return new NextAction(Type.BANK_TRANSFER, null, null, null, expiresAt, details);
    }
}
