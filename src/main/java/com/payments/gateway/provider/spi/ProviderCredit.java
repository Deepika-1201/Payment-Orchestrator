package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * A transfer that arrived in a virtual account (ADR-038). {@code providerReference} is the PSP's id of the credit,
 * {@code collectionReference} the account's, {@code merchantReference} our attempt id where the PSP echoes it.
 * {@code mode} is NEFT, RTGS, IMPS or UPI, or null when the PSP does not say; {@code utr} is the bank's reference.
 */
public record ProviderCredit(String providerReference, String collectionReference, String merchantReference,
                             Money amount, String mode, String utr, Instant receivedAt) {

    public ProviderCredit {
        Objects.requireNonNull(providerReference, "providerReference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
