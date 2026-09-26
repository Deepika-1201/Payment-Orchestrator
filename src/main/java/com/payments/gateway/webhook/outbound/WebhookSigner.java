package com.payments.gateway.webhook.outbound;

import com.payments.gateway.shared.crypto.Hashing;

/**
 * Merchant webhook signatures: {@code PG-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 * Merchants should reject signatures older than 5 minutes.
 */
public final class WebhookSigner {

    public static final String HEADER = "PG-Signature";

    private WebhookSigner() {
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + Hashing.hmacSha256Hex(secret, timestampSeconds + "." + body);
    }

    public static boolean verify(String header, String secret, String body, long nowSeconds, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        long timestamp = -1;
        String signature = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                signature = kv[1];
            }
        }
        if (timestamp < 0 || signature == null || Math.abs(nowSeconds - timestamp) > toleranceSeconds) {
            return false;
        }
        return Hashing.constantTimeEquals(Hashing.hmacSha256Hex(secret, timestamp + "." + body), signature);
    }
}
