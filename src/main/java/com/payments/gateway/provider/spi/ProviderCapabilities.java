package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import java.util.Map;
import java.util.Set;

/**
 * Declares what a provider supports; routing and the orchestrator consult this instead of type checks.
 * {@code requiresCustomerPhone}: the PSP rejects payments without the customer's phone number (Cashfree, ADR-031), so
 * routing skips it for payments that have none. {@code mandates}: recurring instruments it can register (ADR-035).
 */
public record ProviderCapabilities(Map<MethodType, MethodSupport> methods, Set<String> currencies,
                                   boolean voidSupported, boolean partialRefunds, boolean settlementReports,
                                   boolean requiresCustomerPhone, Map<MandateInstrument, MandateSupport> mandates) {

    public ProviderCapabilities {
        methods = Map.copyOf(methods);
        currencies = Set.copyOf(currencies);
        mandates = Map.copyOf(mandates);
    }

    public ProviderCapabilities(Map<MethodType, MethodSupport> methods, Set<String> currencies, boolean voidSupported,
                                boolean partialRefunds, boolean settlementReports, boolean requiresCustomerPhone) {
        this(methods, currencies, voidSupported, partialRefunds, settlementReports, requiresCustomerPhone, Map.of());
    }

    public ProviderCapabilities(Map<MethodType, MethodSupport> methods, Set<String> currencies, boolean voidSupported,
                                boolean partialRefunds, boolean settlementReports) {
        this(methods, currencies, voidSupported, partialRefunds, settlementReports, false);
    }

    /** {@code partialCapture}: a manual capture may take less than the authorization, and the PSP releases the rest (ADR-036). */
    public record MethodSupport(Set<UpiFlow> upiFlows, long minAmount, long maxAmount, boolean manualCapture,
                                boolean partialCapture) {

        public MethodSupport {
            upiFlows = Set.copyOf(upiFlows);
        }

        public MethodSupport(Set<UpiFlow> upiFlows, long minAmount, long maxAmount, boolean manualCapture) {
            this(upiFlows, minAmount, maxAmount, manualCapture, false);
        }
    }

    /** {@code registrationAmount}: what the PSP charges the customer to authorize (0 when free); {@code maxAmount} per debit. */
    public record MandateSupport(long registrationAmount, long maxAmount) {
    }

    public ProviderCapabilities withMandates(Map<MandateInstrument, MandateSupport> supported) {
        return new ProviderCapabilities(methods, currencies, voidSupported, partialRefunds, settlementReports,
                requiresCustomerPhone, supported);
    }

    public boolean supportsMandate(MandateInstrument instrument, Money maxAmount) {
        MandateSupport support = mandates.get(instrument);
        return support != null && currencies.contains(maxAmount.currency()) && maxAmount.amount() <= support.maxAmount();
    }

    public boolean supports(PaymentMethod method, Money amount, CaptureMethod captureMethod, boolean customerPhoneKnown) {
        return (customerPhoneKnown || !requiresCustomerPhone) && supports(method, amount, captureMethod);
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
