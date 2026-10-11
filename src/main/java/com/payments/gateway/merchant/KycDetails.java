package com.payments.gateway.merchant;

import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.web.WireEnums;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public record KycDetails(String legalName, String pan, String gstin, BankAccount bankAccount,
                         List<BeneficialOwner> beneficialOwners, Risk risk, Verification verification,
                         Map<String, Limits> limits) {

    public enum Risk {
        LOW(730), MEDIUM(365), HIGH(180);

        private final int refreshDays;

        Risk(int refreshDays) {
            this.refreshDays = refreshDays;
        }

        @JsonCreator
        public static Risk parse(String value) {
            return WireEnums.parse(Risk.class, value, "risk");
        }

        @JsonValue
        public String wire() {
            return WireEnums.wire(this);
        }

        public Instant expiresAt(Instant verifiedAt) {
            return verifiedAt.plus(Duration.ofDays(refreshDays));
        }
    }

    public record BankAccount(String accountNumber, String ifsc, String beneficiary) {
        public BankAccount {
            pattern(accountNumber, "[0-9]{9,18}", "bank_account.account_number");
            pattern(ifsc, "[A-Z]{4}0[A-Z0-9]{6}", "bank_account.ifsc");
            text(beneficiary, "bank_account.beneficiary", 200);
        }
    }

    public record BeneficialOwner(String name, String pan, int ownershipBps) {
        public BeneficialOwner {
            text(name, "beneficial_owners.name", 200);
            pattern(pan, "[A-Z]{5}[0-9]{4}[A-Z]", "beneficial_owners.pan");
            if (ownershipBps < 1 || ownershipBps > 10_000) {
                throw GatewayException.validation("beneficial_owners.ownership_bps", "must be 1-10000");
            }
        }
    }

    public record Verification(String kycReference, String backgroundReference, String bankReference, Instant verifiedAt) {
        public Verification {
            text(kycReference, "verification.kyc_reference", 200);
            text(backgroundReference, "verification.background_reference", 200);
            text(bankReference, "verification.bank_reference", 200);
            if (verifiedAt == null) {
                throw GatewayException.validation("verification.verified_at", "is required");
            }
        }
    }

    public record Limits(long transactionLimit, long dailyLimit) {
        public Limits {
            if (transactionLimit < 1 || dailyLimit < transactionLimit || dailyLimit > 1_000_000_000_000L) {
                throw GatewayException.validation("limits", "require 1 <= transaction_limit <= daily_limit <= 1000000000000");
            }
        }
    }

    public KycDetails {
        text(legalName, "legal_name", 200);
        pattern(pan, "[A-Z]{5}[0-9]{4}[A-Z]", "pan");
        pattern(gstin, "[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]", "gstin");
        if (!gstin.substring(2, 12).equals(pan)) {
            throw GatewayException.validation("gstin", "must contain the submitted PAN");
        }
        if (bankAccount == null || risk == null || verification == null) {
            throw GatewayException.validation("kyc", "requires bank_account, risk and verification");
        }
        if (beneficialOwners == null || beneficialOwners.isEmpty() || beneficialOwners.size() > 50
                || beneficialOwners.stream().anyMatch(owner -> owner == null)) {
            throw GatewayException.validation("beneficial_owners", "must contain 1-50 owners");
        }
        var identifiers = new HashSet<String>();
        long ownership = 0;
        for (BeneficialOwner owner : beneficialOwners) {
            if (!identifiers.add(owner.pan())) {
                throw GatewayException.validation("beneficial_owners", "must not repeat an owner PAN");
            }
            ownership += owner.ownershipBps();
        }
        if (ownership > 10_000) {
            throw GatewayException.validation("beneficial_owners", "total ownership cannot exceed 10000 basis points");
        }
        if (limits == null || limits.isEmpty() || limits.size() > 20 || !limits.containsKey("INR")) {
            throw GatewayException.validation("limits", "must include INR and at most 20 currencies");
        }
        limits.forEach((currency, limit) -> {
            pattern(currency, "[A-Z]{3}", "limits.currency");
            try {
                if (Currency.getInstance(currency).getDefaultFractionDigits() < 0) {
                    throw new IllegalArgumentException("non-payment currency");
                }
            } catch (IllegalArgumentException exception) {
                throw GatewayException.validation("limits.currency", "must be an ISO payment currency");
            }
            if (limit == null) {
                throw GatewayException.validation("limits", "must not contain null limits");
            }
        });
        beneficialOwners = List.copyOf(beneficialOwners);
        limits = Map.copyOf(limits);
    }

    public void verifyCurrent(Instant now) {
        if (verification.verifiedAt().isAfter(now) || !risk.expiresAt(verification.verifiedAt()).isAfter(now)) {
            throw GatewayException.validation("verification.verified_at", "must be in the past and within the risk refresh period");
        }
    }

    private static void text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw GatewayException.validation(field, "must contain 1-" + maximum + " characters");
        }
    }

    private static void pattern(String value, String expression, String field) {
        if (value == null || !value.matches(expression)) {
            throw GatewayException.validation(field, "has an invalid format");
        }
    }
}