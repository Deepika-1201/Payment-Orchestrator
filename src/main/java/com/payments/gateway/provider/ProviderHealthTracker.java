package com.payments.gateway.provider;

import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.MethodType;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Per-instance routing health: provider-attributable success rate over the last {@value #WINDOW} final outcomes
 * per provider and method, plus an EWMA of call latency (see ADR-009, ADR-011).
 */
@Component
public class ProviderHealthTracker {

    static final int WINDOW = 100;
    static final int MIN_SAMPLES = 20;
    static final double PRIOR_SUCCESS_RATE = 0.95;
    private static final double EWMA_ALPHA = 0.2;

    private final ConcurrentMap<String, OutcomeWindow> outcomes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Ewma> latencies = new ConcurrentHashMap<>();

    public void recordLatency(String provider, Duration duration) {
        latencies.computeIfAbsent(provider, key -> new Ewma()).add(duration.toNanos() / 1_000_000.0);
    }

    /** Customer- and issuer-caused failures are ignored so they do not penalize the provider. */
    public void recordAttemptOutcome(String provider, MethodType method, boolean success, FailureCategory failure) {
        if (!success && (failure == null || !failure.countsAgainstProvider())) {
            return;
        }
        outcomes.computeIfAbsent(provider + "|" + method, key -> new OutcomeWindow()).add(success);
    }

    public double successRate(String provider, MethodType method) {
        OutcomeWindow window = outcomes.get(provider + "|" + method);
        if (window == null) {
            return PRIOR_SUCCESS_RATE;
        }
        return window.rate();
    }

    public int samples(String provider, MethodType method) {
        OutcomeWindow window = outcomes.get(provider + "|" + method);
        return window == null ? 0 : window.size();
    }

    public double latencyMillis(String provider) {
        Ewma ewma = latencies.get(provider);
        return ewma == null ? 0 : ewma.value();
    }

    public double score(String provider, MethodType method) {
        return successRate(provider, method) - 0.1 * Math.min(1.0, latencyMillis(provider) / 5000.0);
    }

    public void reset() {
        outcomes.clear();
        latencies.clear();
    }

    private static final class OutcomeWindow {
        private final boolean[] values = new boolean[WINDOW];
        private int next;
        private int size;
        private int successes;

        synchronized void add(boolean success) {
            if (size == WINDOW) {
                if (values[next]) {
                    successes--;
                }
            } else {
                size++;
            }
            values[next] = success;
            if (success) {
                successes++;
            }
            next = (next + 1) % WINDOW;
        }

        synchronized double rate() {
            return size < MIN_SAMPLES ? PRIOR_SUCCESS_RATE : (double) successes / size;
        }

        synchronized int size() {
            return size;
        }
    }

    private static final class Ewma {
        private double value;
        private boolean initialized;

        synchronized void add(double sample) {
            value = initialized ? EWMA_ALPHA * sample + (1 - EWMA_ALPHA) * value : sample;
            initialized = true;
        }

        synchronized double value() {
            return value;
        }
    }
}
