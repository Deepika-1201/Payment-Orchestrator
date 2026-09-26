package com.payments.gateway.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Test clock that can be moved forward; like the application clock it ticks in microseconds. */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start.truncatedTo(ChronoUnit.MICROS);
    }

    public void set(Instant instant) {
        this.now = instant.truncatedTo(ChronoUnit.MICROS);
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration).truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
