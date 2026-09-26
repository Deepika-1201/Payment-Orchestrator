package com.payments.gateway.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for secrets at rest (webhook secrets, PSP credentials), with a key ring for rotation (ADR-025).
 * New ciphertexts are {@code [2][id length][key id][iv][ciphertext+tag]} under the primary key; older keys stay
 * readable until everything has been re-encrypted. Version-1 ciphertexts ({@code [1][iv][ciphertext+tag]}) belong to
 * the key with id {@value #LEGACY_KEY_ID}.
 */
public final class SecretCipher {

    public static final String LEGACY_KEY_ID = "legacy";
    private static final byte V1 = 1;
    private static final byte V2 = 2;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    private final Map<String, SecretKeySpec> keys;
    private final String primaryKeyId;
    private final SecureRandom random = new SecureRandom();

    /** A single key, used as the legacy key: the configuration before key rotation existed. */
    public SecretCipher(byte[] keyBytes) {
        this(Map.of(LEGACY_KEY_ID, keyBytes), LEGACY_KEY_ID);
    }

    public SecretCipher(Map<String, byte[]> keyRing, String primaryKeyId) {
        Map<String, SecretKeySpec> specs = new LinkedHashMap<>();
        keyRing.forEach((id, bytes) -> {
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("data encryption key ids must match " + KEY_ID + ": " + id);
            }
            if (bytes == null || bytes.length != 32) {
                throw new IllegalArgumentException("data encryption key " + id + " must be exactly 32 bytes (AES-256)");
            }
            specs.put(id, new SecretKeySpec(bytes.clone(), "AES"));
        });
        if (!specs.containsKey(primaryKeyId)) {
            throw new IllegalArgumentException("primary data encryption key " + primaryKeyId + " is not configured");
        }
        this.keys = Map.copyOf(specs);
        this.primaryKeyId = primaryKeyId;
    }

    public String primaryKeyId() {
        return primaryKeyId;
    }

    public byte[] encrypt(String plaintext) {
        return encrypt(plaintext, null);
    }

    public String decrypt(byte[] payload) {
        return decrypt(payload, null);
    }

    /** {@code context} is authenticated but not stored: decryption only succeeds with the same context (e.g. a row id). */
    public byte[] encrypt(String plaintext, String context) {
        byte[] id = primaryKeyId.getBytes(StandardCharsets.US_ASCII);
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(primaryKeyId), new GCMParameterSpec(TAG_BITS, iv));
            if (context != null) {
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            }
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(2 + id.length + IV_LENGTH + ciphertext.length)
                    .put(V2).put((byte) id.length).put(id).put(iv).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(byte[] payload, String context) {
        String keyId = keyId(payload).orElseThrow(() -> new IllegalArgumentException("unsupported ciphertext"));
        SecretKeySpec key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("data encryption key " + keyId + " is not configured");
        }
        int offset = payload[0] == V1 ? 1 : 2 + payload[1];
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, offset, IV_LENGTH));
            if (context != null) {
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plaintext = cipher.doFinal(payload, offset + IV_LENGTH, payload.length - offset - IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }

    /** The id of the key a ciphertext was written with, from its header; empty when the payload is malformed. */
    public static Optional<String> keyId(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        if (payload[0] == V1) {
            return payload.length >= 1 + IV_LENGTH + TAG_BITS / 8 ? Optional.of(LEGACY_KEY_ID) : Optional.empty();
        }
        if (payload[0] != V2 || payload.length < 2) {
            return Optional.empty();
        }
        int idLength = payload[1];
        if (idLength < 1 || payload.length < 2 + idLength + IV_LENGTH + TAG_BITS / 8) {
            return Optional.empty();
        }
        return Optional.of(new String(payload, 2, idLength, StandardCharsets.US_ASCII));
    }

    public boolean isUnderPrimaryKey(byte[] payload) {
        return keyId(payload).filter(primaryKeyId::equals).isPresent() && payload[0] == V2;
    }
}
