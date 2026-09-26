package com.payments.gateway.provider;

import com.payments.gateway.provider.spi.InitiatePaymentRequest;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.VoidRequest;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.shared.web.Mdc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Single entry point for PSP calls: circuit breaking, failure classification, latency metrics. */
@Component
public class ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);

    private final ProviderRegistry registry;
    private final ProviderHealthTracker health;
    private final MeterRegistry meters;
    private final CircuitBreakerRegistry circuitBreakers;

    public ProviderClient(ProviderRegistry registry, ProviderHealthTracker health, MeterRegistry meters) {
        this.registry = registry;
        this.health = health;
        this.meters = meters;
        this.circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(ProviderUnavailableException.class, ProviderTimeoutException.class)
                .build());
    }

    public ProviderPaymentResult initiate(String providerCode, InitiatePaymentRequest request) {
        return call(providerCode, "initiate", provider -> provider.initiatePayment(request));
    }

    public ProviderPaymentResult fetchStatus(String providerCode, PaymentStatusQuery query) {
        return call(providerCode, "status", provider -> provider.fetchPaymentStatus(query));
    }

    public ProviderPaymentResult capture(String providerCode, CaptureRequest request) {
        return call(providerCode, "capture", provider -> provider.capture(request));
    }

    public ProviderPaymentResult voidAuthorization(String providerCode, VoidRequest request) {
        return call(providerCode, "void", provider -> provider.voidAuthorization(request));
    }

    public ProviderRefundResult refund(String providerCode, RefundRequest request) {
        return call(providerCode, "refund", provider -> provider.refund(request));
    }

    public ProviderRefundResult fetchRefundStatus(String providerCode, RefundStatusQuery query) {
        return call(providerCode, "refund_status", provider -> provider.fetchRefundStatus(query));
    }

    public boolean isAvailable(String providerCode) {
        CircuitBreaker.State state = circuitBreakers.circuitBreaker(providerCode).getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }

    public String circuitState(String providerCode) {
        return circuitBreakers.circuitBreaker(providerCode).getState().name();
    }

    public void resetCircuits() {
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private <T> T call(String providerCode, String operation, Function<PaymentProvider, T> action) {
        PaymentProvider provider = registry.require(providerCode);
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(providerCode);
        if (!breaker.tryAcquirePermission()) {
            record(providerCode, operation, "circuit_open", 0);
            throw new ProviderUnavailableException(providerCode, "circuit open");
        }
        MDC.put(Mdc.PROVIDER, providerCode);
        long start = System.nanoTime();
        try {
            T result = action.apply(provider);
            long elapsed = System.nanoTime() - start;
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
            health.recordLatency(providerCode, Duration.ofNanos(elapsed));
            record(providerCode, operation, "ok", elapsed);
            return result;
        } catch (ProviderUnavailableException | ProviderTimeoutException e) {
            long elapsed = System.nanoTime() - start;
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, e);
            record(providerCode, operation, e instanceof ProviderTimeoutException ? "timeout" : "unavailable", elapsed);
            log.warn("Provider call failed: operation={} error={}", operation, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            long elapsed = System.nanoTime() - start;
            ProviderTimeoutException unknown = new ProviderTimeoutException(providerCode,
                    "unexpected adapter error during " + operation, e);
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, unknown);
            record(providerCode, operation, "error", elapsed);
            log.error("Unexpected provider adapter error: operation={}", operation, e);
            throw unknown;
        } finally {
            MDC.remove(Mdc.PROVIDER);
        }
    }

    private void record(String providerCode, String operation, String result, long nanos) {
        Timer.builder("pg.provider.call")
                .tag("provider", providerCode)
                .tag("operation", operation)
                .tag("result", result)
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
