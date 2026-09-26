package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import java.util.Map;
import java.util.Set;

/** Declares what a provider supports; routing and the orchestrator consult this instead of type checks. */
public record ProviderCapabilities(Map<MethodType, MethodSupport> methods, Set<String> currencies,
                                   boolean voidSupported, boolean partialRefunds, boolean settlementReports) {

    public ProviderCapabilities {
        methods = Map.copyOf(methods);
        currencies = Set.copyOf(currencies);
    }

    public record MethodSupport(Set<UpiFlow> upiFlows, long minAmount, long maxAmount, boolean manualCapture) {

        public MethodSupport {
            upiFlows = Set.copyOf(upiFlows);
        }
    }

    public boolean supports(PaymentMethod method, Money amount, CaptureMethod captureMethod) {
        MethodSupport support = methods.get(method.type());
        if (support == null || !currencies.contains(amount.currency())) {
            return false;
        }
        if (method.type() == MethodType.UPI && !support.upiFlows().contains(method.upiFlow())) {
            return false;
        }
        if (amount.amount() < support.minAmount() || amount.amount() > support.maxAmount()) {
            return false;
        }
        return captureMethod != CaptureMethod.MANUAL || support.manualCapture();
    }
}
