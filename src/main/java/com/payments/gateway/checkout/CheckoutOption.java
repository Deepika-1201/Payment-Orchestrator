package com.payments.gateway.checkout;

import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Payment options offered on the hosted checkout page. QR is not offered: the page renders no images yet. */
public enum CheckoutOption {
    UPI_COLLECT(PaymentMethod.upi(UpiFlow.COLLECT, "routing@probe")),
    UPI_INTENT(PaymentMethod.upi(UpiFlow.INTENT, null)),
    UPI_QR(PaymentMethod.upi(UpiFlow.QR, null)),
    CARD(PaymentMethod.card()),
    NETBANKING(PaymentMethod.netbanking("PROBE"));

    // Routing depends only on the method type and UPI flow; the VPA and bank code above are placeholders.
    private final PaymentMethod routingProbe;

    CheckoutOption(PaymentMethod routingProbe) {
        this.routingProbe = routingProbe;
    }

    public PaymentMethod routingProbe() {
        return routingProbe;
    }

    public String formValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<CheckoutOption> fromFormValue(String value) {
        return Arrays.stream(values()).filter(option -> option.formValue().equals(value)).findFirst();
    }

    static List<PaymentMethod> probes() {
        return Arrays.stream(values()).map(CheckoutOption::routingProbe).toList();
    }
}
