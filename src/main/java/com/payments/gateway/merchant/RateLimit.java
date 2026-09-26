package com.payments.gateway.merchant;

/** A token-bucket budget: sustained {@code perSecond}, bursts up to {@code burst} requests. */
public record RateLimit(double perSecond, int burst) {

    public RateLimit {
        if (!(perSecond > 0) || perSecond > 100_000 || burst < 1 || burst > 1_000_000) {
            throw new IllegalArgumentException("rate limit needs 0 < per-second <= 100000 and 1 <= burst <= 1000000");
        }
    }
}
