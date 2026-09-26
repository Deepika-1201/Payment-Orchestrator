package com.payments.gateway.merchant.web;

import com.payments.gateway.merchant.MerchantPrincipal;
import com.payments.gateway.merchant.RateLimit;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.web.ProblemResponses;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-merchant token buckets for the merchant API, separate for reads and writes. Rejections happen before the
 * idempotency layer, so a 429 never consumes an Idempotency-Key and the same key can be retried.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProperties properties;
    private final JsonCodec json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties, JsonCodec json, Clock clock, MeterRegistry meters) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getAttribute(MerchantPrincipal.ATTRIBUTE) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MerchantPrincipal principal = (MerchantPrincipal) request.getAttribute(MerchantPrincipal.ATTRIBUTE);
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        String operation = read ? "read" : "write";
        long now = clock.millis();
        RateLimit override = read ? principal.readLimit() : principal.writeLimit();
        RateLimit limit = override != null ? override : read ? properties.read() : properties.write();
        // A changed limit (merchant override set or removed) starts a fresh bucket at the new budget.
        TokenBucket bucket = buckets.compute(principal.merchantId() + ":" + operation,
                (key, existing) -> existing != null && existing.limit().equals(limit) ? existing : new TokenBucket(limit, now));
        long waitMillis = bucket.tryTake(now);
        if (waitMillis == 0) {
            chain.doFilter(request, response);
            return;
        }
        meters.counter("pg.api.rate_limited", "operation", operation).increment();
        if (bucket.shouldWarn(now)) {
            log.warn("Rate limiting merchant {} ({} requests)", principal.merchantId(), operation);
        }
        long retryAfterSeconds = Math.max(1, (waitMillis + 999) / 1000);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        ProblemResponses.write(response, json, ErrorCode.RATE_LIMITED,
                "Too many " + operation + " requests; retry after " + retryAfterSeconds + " s with the same Idempotency-Key");
    }
}
