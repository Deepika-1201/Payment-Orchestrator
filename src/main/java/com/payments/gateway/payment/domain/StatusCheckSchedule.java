package com.payments.gateway.payment.domain;

import java.time.Duration;
import java.time.Instant;

/** Backoff for PSP status polling; gives up (flags for review) 72h after creation. */
public final class StatusCheckSchedule {

    public static final Duration GIVE_UP_AFTER = Duration.ofHours(72);
    private static final Duration STEADY = Duration.ofHours(2);
    private static final Duration[] DELAYS = {
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1),
            Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(30),
            Duration.ofHours(1)
    };

    private StatusCheckSchedule() {
    }

    public static Duration delay(int checksDone) {
        return checksDone < DELAYS.length ? DELAYS[checksDone] : STEADY;
    }

    public static boolean exhausted(Instant createdAt, Instant now) {
        return Duration.between(createdAt, now).compareTo(GIVE_UP_AFTER) > 0;
    }
}
