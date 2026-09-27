package com.payments.gateway.routing;

import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import java.util.Set;

/** {@code customerPhoneKnown} lets routing skip PSPs that require the customer's phone (ADR-031). */
public record RoutingContext(String merchantId, PaymentMethod method, Money amount, CaptureMethod captureMethod,
                             Set<String> linkedProviders, boolean customerPhoneKnown) {

    public RoutingContext {
        linkedProviders = Set.copyOf(linkedProviders);
    }

    public RoutingContext(String merchantId, PaymentMethod method, Money amount, CaptureMethod captureMethod,
                          Set<String> linkedProviders) {
        this(merchantId, method, amount, captureMethod, linkedProviders, false);
    }
}
