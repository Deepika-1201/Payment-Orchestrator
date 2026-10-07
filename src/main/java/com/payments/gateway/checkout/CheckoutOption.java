package com.payments.gateway.checkout;

import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Payment options offered on the hosted checkout page. QR is not offered: the page renders no images yet. Wallets,
 * cardless EMI and pay later are offered per provider: routing is probed with each provider a PSP declares (ADR-037).
 */
public enum CheckoutOption {
    UPI_COLLECT(PaymentMethod.upi(UpiFlow.COLLECT, "routing@probe")),
    UPI_INTENT(PaymentMethod.upi(UpiFlow.INTENT, null)),
    UPI_QR(PaymentMethod.upi(UpiFlow.QR, null)),
    CARD(PaymentMethod.card()),
    NETBANKING(PaymentMethod.netbanking("PROBE")),
    WALLET(MethodType.WALLET),
    EMI(PaymentMethod.emi()),
    CARDLESS_EMI(MethodType.CARDLESS_EMI),
    PAY_LATER(MethodType.PAY_LATER);

    // Routing depends only on the method type, UPI flow and provider; the VPA and bank code above are placeholders.
    private final PaymentMethod routingProbe;
    private final MethodType providerMethod;

    CheckoutOption(PaymentMethod routingProbe) {
        this.routingProbe = routingProbe;
        this.providerMethod = null;
    }

    CheckoutOption(MethodType providerMethod) {
        this.routingProbe = null;
        this.providerMethod = providerMethod;
    }

    public String formValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean namesProvider() {
        return providerMethod != null;
    }

    /** The method for one of this option's providers. */
    public PaymentMethod forProvider(String provider) {
        return new PaymentMethod(Objects.requireNonNull(providerMethod, name()), null, null, null, null, provider);
    }

    public static Optional<CheckoutOption> fromFormValue(String value) {
        return Arrays.stream(values()).filter(option -> option.formValue().equals(value)).findFirst();
    }

    boolean offeredBy(List<PaymentMethod> routable) {
        return namesProvider() ? !providers(routable).isEmpty() : routable.contains(routingProbe);
    }

    /** The routable providers of this option, in the order they were probed. */
    List<String> providers(List<PaymentMethod> routable) {
        return routable.stream().filter(method -> method.type() == providerMethod).map(PaymentMethod::provider).toList();
    }

    static List<PaymentMethod> probes() {
        return Arrays.stream(values()).map(option -> option.routingProbe).filter(Objects::nonNull).toList();
    }

    static Set<MethodType> providerMethods() {
        return Arrays.stream(values()).map(option -> option.providerMethod).filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(MethodType.class)));
    }
}
