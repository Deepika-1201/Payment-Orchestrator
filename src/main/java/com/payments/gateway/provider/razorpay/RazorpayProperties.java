package com.payments.gateway.provider.razorpay;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Razorpay adapter settings (ADR-030). {@code upiS2s} is true once Razorpay has enabled server-to-server UPI for the
 * platform; until then UPI, like cards and netbanking, completes on a Razorpay-hosted payment page.
 * {@code settlementLag} is the longest time Razorpay takes to settle a capture or refund (ADR-032): T+2 working days,
 * plus weekends and bank holidays.
 */
@ConfigurationProperties("pg.providers.razorpay")
public record RazorpayProperties(boolean enabled,
                                 @DefaultValue("https://api.razorpay.com/v1") URI baseUrl,
                                 boolean upiS2s,
                                 @DefaultValue("15m") Duration hostedPageTtl,
                                 @DefaultValue("5d") Duration settlementLag) {
}
