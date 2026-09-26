package com.payments.gateway.merchant.web;

/** Token bucket holding up to {@code burst} tokens, refilled continuously at {@code perSecond}. */
final class TokenBucket {

    private static final long WARN_INTERVAL_MILLIS = 60_000;

    private final double capacity;
    private final double tokensPerMilli;
    private double tokens;
    private long refilledAtMillis;
    private long warnedAtMillis = Long.MIN_VALUE;

    TokenBucket(RateLimitProperties.Limit limit, long nowMillis) {
        this.capacity = limit.burst();
        this.tokensPerMilli = limit.perSecond() / 1000.0;
        this.tokens = capacity;
        this.refilledAtMillis = nowMillis;
    }

    /** Takes a token and returns 0, or returns the milliseconds until a token will be available. */
    synchronized long tryTake(long nowMillis) {
        long elapsed = nowMillis - refilledAtMillis;
        // A clock that moves backwards simply refills nothing until it catches up.
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * tokensPerMilli);
            refilledAtMillis = nowMillis;
        }
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return Math.max(1, (long) Math.ceil((1 - tokens) / tokensPerMilli));
    }

    /** True at most once per minute, to log sustained throttling without flooding the logs. */
    synchronized boolean shouldWarn(long nowMillis) {
        if (warnedAtMillis != Long.MIN_VALUE && nowMillis - warnedAtMillis < WARN_INTERVAL_MILLIS) {
            return false;
        }
        warnedAtMillis = nowMillis;
        return true;
    }
}
