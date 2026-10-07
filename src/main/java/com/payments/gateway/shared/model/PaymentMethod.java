package com.payments.gateway.shared.model;

import java.util.Objects;

/**
 * Non-sensitive payment method selection. Card details are never carried here: cards are collected on
 * PSP-hosted pages (see ADR-008). {@code MANDATE} attempts debit an authorized mandate (ADR-035).
 */
public record PaymentMethod(MethodType type, UpiFlow upiFlow, String vpa, String bankCode, String mandateId) {

    public PaymentMethod {
        Objects.requireNonNull(type, "type");
        if (type != MethodType.MANDATE) {
            mandateId = null;
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
            case CARD -> {
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

    public PaymentMethod(MethodType type, UpiFlow upiFlow, String vpa, String bankCode) {
        this(type, upiFlow, vpa, bankCode, null);
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
}
