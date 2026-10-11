package com.payments.gateway.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

public final class BillingAmounts {

    public static final int GST_BPS = 1800;
    public static final long MAX_SEQUENCE = 9_999_999;
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private BillingAmounts() {
    }

    public record Price(long fixedAmount, int rateBps) {
        public Price {
            if (fixedAmount < 0 || fixedAmount > 1_000_000_000_000L || rateBps < 0 || rateBps > 10_000) {
                throw new IllegalArgumentException("invalid billing price");
            }
        }

        public long charge(long amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must not be negative");
            }
            return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rateBps)).movePointLeft(4)
                    .add(BigDecimal.valueOf(fixedAmount)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
    }

    public static long tax(long subtotal) {
        if (subtotal < 0) {
            throw new IllegalArgumentException("subtotal must not be negative");
        }
        return BigDecimal.valueOf(subtotal).multiply(BigDecimal.valueOf(GST_BPS)).movePointLeft(4)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static long allocateTax(long totalBase, long totalTax, long previousBase, long amount) {
        if (totalBase < 0 || totalTax < 0 || previousBase < 0 || previousBase > totalBase
                || amount < 0 || amount > totalBase - previousBase || (totalBase == 0 && totalTax != 0)) {
            throw new IllegalArgumentException("tax allocation exceeds the original line");
        }
        if (totalBase == 0 || amount == 0) {
            return 0;
        }
        BigDecimal denominator = BigDecimal.valueOf(totalBase);
        BigDecimal tax = BigDecimal.valueOf(totalTax);
        long before = tax.multiply(BigDecimal.valueOf(previousBase)).divide(denominator, 0, RoundingMode.HALF_UP).longValueExact();
        long after = tax.multiply(BigDecimal.valueOf(Math.addExact(previousBase, amount)))
                .divide(denominator, 0, RoundingMode.HALF_UP).longValueExact();
        return Math.subtractExact(after, before);
    }

    public static int financialYear(Instant issuedAt) {
        var date = issuedAt.atZone(ZONE).toLocalDate();
        return date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    }

    public static String number(String series, int year, long sequence) {
        if ((!series.equals("INV") && !series.equals("CN")) || year < 1000 || year > 9999
                || sequence < 1 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("invalid or exhausted billing document series");
        }
        return String.format(Locale.ROOT, "%s/%04d/%06d", series, year, sequence);
    }
}