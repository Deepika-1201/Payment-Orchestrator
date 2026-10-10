package com.payments.gateway.shared.model;

import java.util.Objects;

/** Where a customer sends a bank transfer: the PSP's virtual account and, where issued, its UPI ID (ADR-038). */
public record BankTransferDetails(String accountNumber, String ifsc, String beneficiaryName, String bankName,
                                  String vpa) {

    public BankTransferDetails {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(ifsc, "ifsc");
    }
}
