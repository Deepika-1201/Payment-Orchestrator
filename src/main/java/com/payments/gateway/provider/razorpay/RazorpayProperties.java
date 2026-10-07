package com.payments.gateway.provider.razorpay;

import com.payments.gateway.shared.model.MethodType;
import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Razorpay adapter settings (ADR-030). {@code upiS2s} is true once Razorpay has enabled server-to-server UPI for the
 * platform; until then UPI, like cards and netbanking, completes on a Razorpay-hosted payment page.
 * {@code settlementLag} is the longest time Razorpay takes to settle a capture or refund (ADR-032): T+2 working days,
 * plus weekends and bank holidays. {@code mandates} is true once Razorpay has enabled recurring payments (ADR-035).
 * {@code extraMethods} lists the wallet, card EMI, cardless EMI and pay-later methods Razorpay has enabled (ADR-037).
 */
@ConfigurationProperties("pg.providers.razorpay")
public record RazorpayProperties(boolean enabled,
                                 @DefaultValue("https://api.razorpay.com/v1") URI baseUrl,
                                 boolean upiS2s,
                                 @DefaultValue("15m") Duration hostedPageTtl,
                                 @DefaultValue("5d") Duration settlementLag,
                                 boolean mandates,
                                 @DefaultValue Set<MethodType> extraMethods) {

    static final Set<MethodType> OPTIONAL_METHODS = EnumSet.of(MethodType.WALLET, MethodType.EMI, MethodType.CARDLESS_EMI,
            MethodType.PAY_LATER);

    public RazorpayProperties {
        extraMethods = Set.copyOf(extraMethods);
        if (!OPTIONAL_METHODS.containsAll(extraMethods)) {
            throw new IllegalArgumentException(
                    "pg.providers.razorpay.extra-methods accepts wallet, emi, cardless_emi and pay_later, not " + extraMethods);
        }
    }
}
