package com.payments.gateway.routing;

import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import java.util.Set;

public record RoutingContext(String merchantId, PaymentMethod method, Money amount, CaptureMethod captureMethod,
                             Set<String> linkedProviders) {

    public RoutingContext {
        linkedProviders = Set.copyOf(linkedProviders);
    }
}
