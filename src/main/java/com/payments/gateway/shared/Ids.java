package com.payments.gateway.shared;

import java.security.SecureRandom;

/** Prefixed, time-ordered identifiers: {@code prefix_} + 26-char Crockford base32 ULID. */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String newId(String prefix) {
        return prefix + "_" + ulid(System.currentTimeMillis());
    }

    static String ulid(long timeMillis) {
        char[] out = new char[26];
        long time = timeMillis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long high = 0;
        long low = 0;
        for (int i = 0; i < 5; i++) {
            high = (high << 8) | (random[i] & 0xFFL);
            low = (low << 8) | (random[i + 5] & 0xFFL);
        }
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (high & 31)];
            high >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (low & 31)];
            low >>>= 5;
        }
        return new String(out);
    }
}
