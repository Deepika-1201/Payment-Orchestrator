package com.payments.gateway.shared.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Non-sensitive payment method selection. Card details are never carried here: cards are collected on
 * PSP-hosted pages (see ADR-008). {@code MANDATE} attempts debit an authorized mandate (ADR-035). {@code provider}
 * names the wallet or lender of {@code WALLET}, {@code CARDLESS_EMI} and {@code PAY_LATER} payments (ADR-037).
 */
public record PaymentMethod(MethodType type, UpiFlow upiFlow, String vpa, String bankCode, String mandateId,
                            String provider) {

    private static final Pattern PROVIDER = Pattern.compile("[a-z0-9_]{2,32}");

    public PaymentMethod {
        Objects.requireNonNull(type, "type");
        if (type != MethodType.MANDATE) {
            mandateId = null;
        }
        if (type != MethodType.WALLET && type != MethodType.CARDLESS_EMI && type != MethodType.PAY_LATER) {
            provider = null;
        } else if (provider == null || !PROVIDER.matcher(provider).matches()) {
            throw new IllegalArgumentException(type.name().toLowerCase(Locale.ROOT)
                    + ".provider is required: 2-32 lower-case letters, digits or _");
        }
        switch (type) {
            case UPI -> {
                if (upiFlow == null) {
                    throw new IllegalArgumentException("upi.flow is required for UPI payments");
                }
                if (upiFlow == UpiFlow.COLLECT && (vpa == null || vpa.isBlank())) {
                    throw new IllegalArgumentException("upi.vpa is required for UPI collect");
                }
                if (upiFlow != UpiFlow.COLLECT) {
                    vpa = null;
                }
                bankCode = null;
            }
            case NETBANKING -> {
                if (bankCode == null || bankCode.isBlank()) {
                    throw new IllegalArgumentException("netbanking.bank_code is required for netbanking payments");
                }
                upiFlow = null;
                vpa = null;
            }
            case CARD, WALLET, EMI, CARDLESS_EMI, PAY_LATER, BANK_TRANSFER -> {
                upiFlow = null;
                vpa = null;
                bankCode = null;
            }
            case MANDATE -> {
                if (mandateId == null || mandateId.isBlank()) {
                    throw new IllegalArgumentException("mandate_id is required for mandate payments");
                }
                upiFlow = null;
                vpa = null;
                bankCode = null;
            }
        }
    }

    public PaymentMethod(MethodType type, UpiFlow upiFlow, String vpa, String bankCode, String mandateId) {
        this(type, upiFlow, vpa, bankCode, mandateId, null);
    }

    public PaymentMethod(MethodType type, UpiFlow upiFlow, String vpa, String bankCode) {
        this(type, upiFlow, vpa, bankCode, null, null);
    }

    public static PaymentMethod upi(UpiFlow flow, String vpa) {
        return new PaymentMethod(MethodType.UPI, flow, vpa, null);
    }

    public static PaymentMethod card() {
        return new PaymentMethod(MethodType.CARD, null, null, null);
    }

    public static PaymentMethod netbanking(String bankCode) {
        return new PaymentMethod(MethodType.NETBANKING, null, null, bankCode);
    }

    public static PaymentMethod mandate(String mandateId) {
        return new PaymentMethod(MethodType.MANDATE, null, null, null, mandateId);
    }

    public static PaymentMethod wallet(String provider) {
        return new PaymentMethod(MethodType.WALLET, null, null, null, null, provider);
    }

    public static PaymentMethod emi() {
        return new PaymentMethod(MethodType.EMI, null, null, null);
    }

    public static PaymentMethod cardlessEmi(String provider) {
        return new PaymentMethod(MethodType.CARDLESS_EMI, null, null, null, null, provider);
    }

    public static PaymentMethod payLater(String provider) {
        return new PaymentMethod(MethodType.PAY_LATER, null, null, null, null, provider);
    }

    public static PaymentMethod bankTransfer() {
        return new PaymentMethod(MethodType.BANK_TRANSFER, null, null, null);
    }
}
