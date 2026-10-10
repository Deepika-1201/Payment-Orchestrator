package com.payments.gateway.shared.crypto;

import java.util.List;

/**
 * Ciphertexts written with the data key ring by another module, which a key rotation must also rewrite (ADR-025).
 * {@code context} is the authenticated context they were encrypted with.
 */
public interface KeyRingCiphertexts {

    record Stored(String rowId, byte[] ciphertext, String context) {
    }

    List<Stored> keyRingCiphertexts();

    /** Replaces the ciphertext only if it is still {@code expected}; returns whether it did. */
    boolean swapKeyRingCiphertext(String rowId, byte[] expected, byte[] replacement);
}
