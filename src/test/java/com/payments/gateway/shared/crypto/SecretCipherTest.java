package com.payments.gateway.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final byte[] OLD = key(1);
    private static final byte[] NEW = key(2);

    @Test
    void versionOneCiphertextsStillDecryptWithTheLegacyKey() throws Exception {
        byte[] v1 = legacyEncrypt(OLD, "whsec_before_rotation", "ctx");

        assertThat(SecretCipher.keyId(v1)).contains(SecretCipher.LEGACY_KEY_ID);
        assertThat(new SecretCipher(OLD).decrypt(v1, "ctx")).isEqualTo("whsec_before_rotation");
        assertThat(new SecretCipher(Map.of("legacy", OLD, "k2", NEW), "k2").decrypt(v1, "ctx")).isEqualTo("whsec_before_rotation");
    }

    @Test
    void theRingEncryptsWithThePrimaryKeyAndReadsOlderKeysUntilTheyAreRemoved() {
        SecretCipher before = new SecretCipher(Map.of("k1", OLD), "k1");
        SecretCipher during = new SecretCipher(Map.of("k1", OLD, "k2", NEW), "k2");
        SecretCipher after = new SecretCipher(Map.of("k2", NEW), "k2");

        byte[] old = before.encrypt("secret", "row-1");
        byte[] fresh = during.encrypt("secret", "row-1");

        assertThat(SecretCipher.keyId(old)).contains("k1");
        assertThat(SecretCipher.keyId(fresh)).contains("k2");
        assertThat(during.decrypt(old, "row-1")).isEqualTo("secret");
        assertThat(during.isUnderPrimaryKey(old)).isFalse();
        assertThat(during.isUnderPrimaryKey(fresh)).isTrue();
        assertThat(after.decrypt(fresh, "row-1")).isEqualTo("secret");
        assertThatThrownBy(() -> after.decrypt(old, "row-1")).hasMessageContaining("k1 is not configured");
        assertThatThrownBy(() -> during.decrypt(fresh, "row-2")).as("context still authenticated")
                .hasMessageContaining("decryption failed");
        assertThatThrownBy(() -> new SecretCipher(Map.of("k1", OLD), "k9")).hasMessageContaining("k9 is not configured");
        assertThat(SecretCipher.keyId(new byte[] {9, 1, 2})).isEmpty();
    }

    /** The pre-rotation format: [1][iv][ciphertext+tag]. */
    private static byte[] legacyEncrypt(byte[] key, String plaintext, String context) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(1 + iv.length + ciphertext.length).put((byte) 1).put(iv).put(ciphertext).array();
    }

    private static byte[] key(int seed) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) seed);
        return key;
    }
}
