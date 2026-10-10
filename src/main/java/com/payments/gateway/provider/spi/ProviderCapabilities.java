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
 * {@code disputeResponses}: merchants can contest or accept disputes through the gateway (ADR-039).
 * {@code foreignCurrencies}: the other currencies it charges international cards in (ADR-040); {@code currencies}
 * are those every method takes.
 */
public record ProviderCapabilities(Map<MethodType, MethodSupport> methods, Set<String> currencies,
                                   boolean voidSupported, boolean partialRefunds, boolean settlementReports,
                                   boolean requiresCustomerPhone, Map<MandateInstrument, MandateSupport> mandates,
                                   boolean disputeResponses, Map<String, CurrencySupport> foreignCurrencies) {

    public ProviderCapabilities {
        methods = Map.copyOf(methods);
        currencies = Set.copyOf(currencies);
        mandates = Map.copyOf(mandates);
        foreignCurrencies = Map.copyOf(foreignCurrencies);
    }

    public ProviderCapabilities(Map<MethodType, MethodSupport> methods, Set<String> currencies, boolean voidSupported,
                    boolean partialRefunds, boolean settlementReports, boolean requiresCustomerPhone,
                    Map<MandateInstrument, MandateSupport> mandates, boolean disputeResponses) {
        this(methods, currencies, voidSupported, partialRefunds, settlementReports, requiresCustomerPhone, mandates,
            disputeResponses, Map.of());
        }

        public ProviderCapabilities(Map<MethodType, MethodSupport> methods, Set<String> currencies, boolean voidSupported,
                                boolean partialRefunds, boolean settlementReports, boolean requiresCustomerPhone,
                                Map<MandateInstrument, MandateSupport> mandates) {
        this(methods, currencies, voidSupported, partialRefunds, settlementReports, requiresCustomerPhone, mandates, false,
                Map.of());
    }

    public ProviderCapabilities(Map<MethodType, MethodSupport> methods, Set<String> currencies, boolean voidSupported,
                                boolean partialRefunds, boolean settlementReports, boolean requiresCustomerPhone) {
        this(methods, currencies, voidSupported, partialRefunds, settlementReports, requiresCustomerPhone, Map.of());
    }

    public ProviderCapabilities(Map<MethodType, MethodSupport> methods, Set<String> currencies, boolean voidSupported,
                                boolean partialRefunds, boolean settlementReports) {
        this(methods, currencies, voidSupported, partialRefunds, settlementReports, false);
    }

    /**
     * {@code partialCapture}: a manual capture may take less than the authorization, and the PSP releases the rest
     * (ADR-036). {@code providers}: the wallets or lenders offered; {@code tenures}: card EMI plans in months (ADR-037).
     */
    public record MethodSupport(Set<UpiFlow> upiFlows, long minAmount, long maxAmount, boolean manualCapture,
                                boolean partialCapture, Set<String> providers, Set<Integer> tenures) {

        public MethodSupport {
            upiFlows = Set.copyOf(upiFlows);
            providers = Set.copyOf(providers);
            tenures = Set.copyOf(tenures);
        }

        public MethodSupport(Set<UpiFlow> upiFlows, long minAmount, long maxAmount, boolean manualCapture,
                             boolean partialCapture) {
            this(upiFlows, minAmount, maxAmount, manualCapture, partialCapture, Set.of(), Set.of());
        }

        public MethodSupport(Set<UpiFlow> upiFlows, long minAmount, long maxAmount, boolean manualCapture) {
            this(upiFlows, minAmount, maxAmount, manualCapture, false);
        }

        /** A wallet, cardless EMI or pay-later method offering these providers. */
        public static MethodSupport ofProviders(Set<String> providers, long minAmount, long maxAmount) {
            return new MethodSupport(Set.of(), minAmount, maxAmount, false, false, providers, Set.of());
        }

        public static MethodSupport emi(Set<Integer> tenures, long minAmount, long maxAmount) {
            return new MethodSupport(Set.of(), minAmount, maxAmount, false, false, Set.of(), tenures);
        }
    }

    /** {@code registrationAmount}: what the PSP charges the customer to authorize (0 when free); {@code maxAmount} per debit. */
    public record MandateSupport(long registrationAmount, long maxAmount) {
    }

    /** Card payments in another currency: the amount range in its minor units, and the multiple amounts must be of. */
    public record CurrencySupport(long minAmount, long maxAmount, long step) {

        public CurrencySupport {
            if (minAmount < 1 || maxAmount < minAmount || step < 1) {
                throw new IllegalArgumentException("invalid currency support: " + minAmount + ".." + maxAmount + " step " + step);
            }
        }

        public boolean allows(long amount) {
            return amount >= minAmount && amount <= maxAmount && amount % step == 0;
        }
    }

    public ProviderCapabilities withMandates(Map<MandateInstrument, MandateSupport> supported) {
        return new ProviderCapabilities(methods, currencies, voidSupported, partialRefunds, settlementReports,
                requiresCustomerPhone, supported, disputeResponses, foreignCurrencies);
    }

    public ProviderCapabilities withDisputeResponses() {
        return new ProviderCapabilities(methods, currencies, voidSupported, partialRefunds, settlementReports,
                requiresCustomerPhone, mandates, true, foreignCurrencies);
    }

    public ProviderCapabilities withForeignCurrencies(Map<String, CurrencySupport> supported) {
        return new ProviderCapabilities(methods, currencies, voidSupported, partialRefunds, settlementReports,
                requiresCustomerPhone, mandates, disputeResponses, supported);
    }

    /** The amount step for this currency: 1 unless the PSP charges cards in it with a coarser step. */
    public long step(String currency) {
        CurrencySupport support = foreignCurrencies.get(currency);
        return support == null ? 1 : support.step();
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
        if (support == null) {
            return false;
        }
        if (!currencies.contains(amount.currency())) {
            CurrencySupport foreign = foreignCurrencies.get(amount.currency());
            return method.type() == MethodType.CARD && foreign != null && foreign.allows(amount.amount())
                    && (captureMethod != CaptureMethod.MANUAL || support.manualCapture());
        }
        if (method.type() == MethodType.UPI && !support.upiFlows().contains(method.upiFlow())) {
            return false;
        }
        if (method.provider() != null && !support.providers().contains(method.provider())) {
            return false;
        }
        if (amount.amount() < support.minAmount() || amount.amount() > support.maxAmount()) {
            return false;
        }
        return captureMethod != CaptureMethod.MANUAL || support.manualCapture();
    }
}
