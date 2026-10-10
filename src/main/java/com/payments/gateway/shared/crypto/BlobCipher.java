package com.payments.gateway.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Files at rest (ADR-039): each under a random AES-256-GCM key of its own, bound to {@code context}, with that key
 * stored wrapped by the data key ring. Rotating a data key re-wraps the file keys, never the files (ADR-025).
 */
public final class BlobCipher {

    private static final int KEY_BYTES = 32;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    /** What encryption adds to a file: the IV and the GCM tag. */
    public static final int OVERHEAD = IV_LENGTH + TAG_BITS / 8;

    public record Sealed(byte[] content, byte[] wrappedKey) {
    }

    private final SecretCipher keyRing;
    private final SecureRandom random = new SecureRandom();

    public BlobCipher(SecretCipher keyRing) {
        this.keyRing = keyRing;
    }

    public Sealed seal(byte[] plaintext, String context) {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] content = ByteBuffer.allocate(IV_LENGTH + ciphertext.length).put(iv).put(ciphertext).array();
            return new Sealed(content, keyRing.encrypt(Base64.getEncoder().encodeToString(key), context));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public byte[] open(byte[] content, byte[] wrappedKey, String context) {
        byte[] key = Base64.getDecoder().decode(keyRing.decrypt(wrappedKey, context));
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, content, 0, IV_LENGTH));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(content, IV_LENGTH, content.length - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
