package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.RateLimit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void allowsTheBurstThenRefillsAtTheConfiguredRate() {
        TokenBucket bucket = new TokenBucket(new RateLimit(2, 3), 0);
        for (int i = 0; i < 3; i++) {
            assertThat(bucket.tryTake(0)).isZero();
        }

        assertThat(bucket.tryTake(0)).isBetween(500L, 501L);
        assertThat(bucket.tryTake(250)).isBetween(250L, 251L);
        assertThat(bucket.tryTake(510)).isZero();
        assertThat(bucket.tryTake(510)).isPositive();
    }

    @Test
    void neverRefillsBeyondTheBurst() {
        TokenBucket bucket = new TokenBucket(new RateLimit(10, 2), 0);

        assertThat(bucket.tryTake(3_600_000)).isZero();
        assertThat(bucket.tryTake(3_600_000)).isZero();
        assertThat(bucket.tryTake(3_600_000)).isPositive();
    }

    @Test
    void aClockMovingBackwardsGrantsNothingExtra() {
        TokenBucket bucket = new TokenBucket(new RateLimit(1, 1), 10_000);
        assertThat(bucket.tryTake(10_000)).isZero();

        assertThat(bucket.tryTake(5_000)).isBetween(1_000L, 1_001L);
        assertThat(bucket.tryTake(10_500)).isBetween(500L, 501L);
        assertThat(bucket.tryTake(11_100)).isZero();
    }

    @Test
    void warnsAtMostOncePerMinute() {
        TokenBucket bucket = new TokenBucket(new RateLimit(1, 1), 0);

        assertThat(bucket.shouldWarn(0)).isTrue();
        assertThat(bucket.shouldWarn(59_999)).isFalse();
        assertThat(bucket.shouldWarn(60_000)).isTrue();
    }
}
