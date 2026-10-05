package com.payments.gateway.provider.cashfree;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cashfree adapter settings (ADR-031). {@code baseUrl} defaults to Cashfree's sandbox on a TEST deployment and to its
 * production API on LIVE. {@code upiS2s} is true once Cashfree has enabled Order Pay (server-to-server) for the platform;
 * until then UPI, like cards and netbanking, completes on a Cashfree-hosted Payment Link. {@code settlementLag} is the
 * longest time Cashfree takes to settle a capture or refund (ADR-032).
 */
@ConfigurationProperties("pg.providers.cashfree")
public record CashfreeProperties(boolean enabled,
                                 URI baseUrl,
                                 @DefaultValue("2025-01-01") String apiVersion,
                                 boolean upiS2s,
                                 @DefaultValue("15m") Duration hostedPageTtl,
                                 @DefaultValue("5d") Duration settlementLag) {

    static final URI SANDBOX = URI.create("https://sandbox.cashfree.com/pg");
    static final URI PRODUCTION = URI.create("https://api.cashfree.com/pg");
}
