package com.payments.gateway.provider.razorpay;

import com.payments.gateway.shared.model.MethodType;
import java.net.URI;
import java.time.Duration;
import java.util.Currency;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
                                 @DefaultValue Set<MethodType> extraMethods,
                                 Map<String, Long> foreignCurrencies) {

    static final Set<MethodType> OPTIONAL_METHODS = EnumSet.of(MethodType.WALLET, MethodType.EMI, MethodType.CARDLESS_EMI,
            MethodType.PAY_LATER, MethodType.BANK_TRANSFER);

    @ConstructorBinding
    public RazorpayProperties {
        extraMethods = Set.copyOf(extraMethods);
        foreignCurrencies = foreignCurrencies == null ? Map.of() : Map.copyOf(foreignCurrencies);
        foreignCurrencies.forEach((currency, maximum) -> {
            int exponent = Currency.getInstance(currency).getDefaultFractionDigits();
            if (currency.equals("INR") || (exponent != 0 && exponent != 2 && exponent != 3)
                    || maximum < 100 || maximum > 1_000_000_000_000L) {
                throw new IllegalArgumentException("invalid foreign currency or maximum amount: " + currency + "=" + maximum);
            }
        });
        if (!OPTIONAL_METHODS.containsAll(extraMethods)) {
            throw new IllegalArgumentException("pg.providers.razorpay.extra-methods accepts wallet, emi, cardless_emi, "
                    + "pay_later and bank_transfer, not " + extraMethods);
        }
    }

    public RazorpayProperties(boolean enabled, URI baseUrl, boolean upiS2s, Duration hostedPageTtl,
                              Duration settlementLag, boolean mandates, Set<MethodType> extraMethods) {
        this(enabled, baseUrl, upiS2s, hostedPageTtl, settlementLag, mandates, extraMethods, Map.of());
    }
}
