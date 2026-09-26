package com.payments.gateway.webhook.outbound;

import com.payments.gateway.shared.crypto.Hashing;
import java.util.ArrayList;
import java.util.List;

/**
 * Merchant webhook signatures: {@code PG-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 * During a secret rotation there is one {@code v1} per valid secret; a receiver accepts the delivery if any matches.
 * Merchants should reject signatures older than 5 minutes.
 */
public final class WebhookSigner {

    public static final String HEADER = "PG-Signature";

    private WebhookSigner() {
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        return sign(List.of(secret), timestampSeconds, body);
    }

    public static String sign(List<String> secrets, long timestampSeconds, String body) {
        StringBuilder header = new StringBuilder("t=").append(timestampSeconds);
        for (String secret : secrets) {
            header.append(",v1=").append(Hashing.hmacSha256Hex(secret, timestampSeconds + "." + body));
        }
        return header.toString();
    }

    public static boolean verify(String header, String secret, String body, long nowSeconds, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        long timestamp = -1;
        List<String> signatures = new ArrayList<>();
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
                signatures.add(kv[1]);
            }
        }
        if (timestamp < 0 || signatures.isEmpty() || Math.abs(nowSeconds - timestamp) > toleranceSeconds) {
            return false;
        }
        String expected = Hashing.hmacSha256Hex(secret, timestamp + "." + body);
        return signatures.stream().anyMatch(signature -> Hashing.constantTimeEquals(expected, signature));
    }
}
