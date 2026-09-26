package com.payments.gateway.shared.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last line of defence for log output (NFR-9, ADR-022): masks secrets, card numbers and personal data that should
 * never have been logged in the first place. Code must still not log them; this only limits the damage.
 */
public final class LogRedactor {

    private static final Pattern API_KEY = Pattern.compile("\\bsk_(test|live)_[A-Za-z0-9_-]{6,}");
    private static final Pattern WEBHOOK_SECRET = Pattern.compile("\\bwhsec_[A-Za-z0-9_-]{6,}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(://)[^:/@\\s]+:[^@\\s]+@");
    private static final Pattern EMAIL_OR_VPA = Pattern.compile("\\b([A-Za-z0-9])[A-Za-z0-9._%+-]*@([A-Za-z][A-Za-z0-9.-]*)\\b");
    /** Card-number candidates: 13-19 digits, optionally in groups, starting like a card network (2-6). */
    private static final Pattern PAN = Pattern.compile("\\b[2-6](?:[ -]?\\d){12,18}\\b");

    private LogRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = API_KEY.matcher(text).replaceAll("sk_$1_***");
        result = WEBHOOK_SECRET.matcher(result).replaceAll("whsec_***");
        result = BEARER.matcher(result).replaceAll("$1***");
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1***:***@");
        result = EMAIL_OR_VPA.matcher(result).replaceAll("$1***@$2");
        return maskCardNumbers(result);
    }

    private static String maskCardNumbers(String text) {
        Matcher matcher = PAN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[ -]", "");
            String replacement = luhnValid(digits) ? "****" + digits.substring(digits.length() - 4) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubled) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }
}
