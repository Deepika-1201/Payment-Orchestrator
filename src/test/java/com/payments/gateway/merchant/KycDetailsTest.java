package com.payments.gateway.merchant;

import com.payments.gateway.merchant.KycDetails.BankAccount;
import com.payments.gateway.merchant.KycDetails.BeneficialOwner;
import com.payments.gateway.merchant.KycDetails.Limits;
import com.payments.gateway.merchant.KycDetails.Risk;
import com.payments.gateway.merchant.KycDetails.Verification;
import com.payments.gateway.shared.error.GatewayException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KycDetailsTest {
    private static final Instant VERIFIED = Instant.parse("2026-10-11T00:00:00Z");

        @Test
        void aggregationIsOptInAndRequiresACollectionOwner() {
                assertThatCode(() -> new AggregationProperties(false, false, null, null)).doesNotThrowAnyException();
                assertThatThrownBy(() -> new AggregationProperties(true, false, null, null)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> new AggregationProperties(true, false, " ", null)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> new AggregationProperties(false, true, "owner", null)).isInstanceOf(IllegalArgumentException.class);
                assertThatCode(() -> new AggregationProperties(true, true, "owner", null)).doesNotThrowAnyException();
        }

    static KycDetails details(Risk risk, Instant verifiedAt) {
        return new KycDetails("Example Merchant", "ABCDE1234F", "27ABCDE1234F1Z5",
                new BankAccount("123456789012", "HDFC0001234", "Example Merchant"),
                List.of(new BeneficialOwner("Example Owner", "BCDEF2345G", 10_000)), risk,
                new Verification("kyc-check-123", "background-check-456", "bank-check-789", verifiedAt),
                Map.of("INR", new Limits(100_000, 250_000)));
    }

    @Test
    void validityUsesEvidenceDateAndExclusiveRefreshBoundary() {
        int[] periods = {730, 365, 180};
        for (int index = 0; index < Risk.values().length; index++) {
            KycDetails details = details(Risk.values()[index], VERIFIED);
            Instant expiry = VERIFIED.plus(Duration.ofDays(periods[index]));
            assertThat(details.risk().expiresAt(VERIFIED)).isEqualTo(expiry);
            assertThatCode(() -> details.verifyCurrent(expiry.minusNanos(1))).doesNotThrowAnyException();
            assertThatThrownBy(() -> details.verifyCurrent(expiry)).isInstanceOf(GatewayException.class);
            assertThatThrownBy(() -> details.verifyCurrent(VERIFIED.minusNanos(1))).isInstanceOf(GatewayException.class);
        }
    }

    @Test
    void validatesIdentityEvidenceBankAndBusinessLimits() {
        assertThatThrownBy(() -> new BankAccount("123", "HDFC0001234", "Name")).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new BankAccount("123456789", "HDFC1001234", "Name")).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new BankAccount("123456789", "HDFC0001234", " ")).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new Verification(" ", "background", "bank", VERIFIED)).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new Verification("kyc", "", "bank", VERIFIED)).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new Verification("kyc", "background", "", VERIFIED)).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new Limits(0, 10)).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new Limits(11, 10)).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new Limits(1, 1_000_000_000_001L)).isInstanceOf(GatewayException.class);
        assertThatCode(() -> new Limits(1_000_000_000_000L, 1_000_000_000_000L)).doesNotThrowAnyException();
        KycDetails valid = details(Risk.HIGH, VERIFIED);
        assertThatThrownBy(() -> new KycDetails(valid.legalName(), valid.pan(), "27FGHIJ5678K1Z5", valid.bankAccount(),
                valid.beneficialOwners(), valid.risk(), valid.verification(), valid.limits())).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new KycDetails(valid.legalName(), valid.pan(), valid.gstin(), valid.bankAccount(),
                valid.beneficialOwners(), valid.risk(), valid.verification(), Map.of("XXX", new Limits(1, 2))))
                .isInstanceOf(GatewayException.class);
        for (String currency : List.of("ZZZ", "XXX", "inr")) {
            assertThatThrownBy(() -> new KycDetails(valid.legalName(), valid.pan(), valid.gstin(), valid.bankAccount(),
                    valid.beneficialOwners(), valid.risk(), valid.verification(),
                    Map.of("INR", new Limits(1, 2), currency, new Limits(1, 2)))).isInstanceOf(GatewayException.class);
        }
        assertThatThrownBy(() -> new KycDetails(valid.legalName(), valid.pan(), valid.gstin(), valid.bankAccount(),
                List.of(), valid.risk(), valid.verification(), valid.limits())).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new BeneficialOwner("Owner", "BCDEF2345G", 0)).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new BeneficialOwner("Owner", "BCDEF2345G", 10_001)).isInstanceOf(GatewayException.class);
    }

    @Test
    void ownershipIsBoundedAndCollectionsAreDefensivelyCopied() {
        KycDetails valid = details(Risk.MEDIUM, VERIFIED);
        var owners = new ArrayList<>(valid.beneficialOwners());
        var limits = new HashMap<>(valid.limits());
        KycDetails copied = new KycDetails(valid.legalName(), valid.pan(), valid.gstin(), valid.bankAccount(), owners,
                valid.risk(), valid.verification(), limits);
        owners.clear();
        limits.clear();
        assertThat(copied.beneficialOwners()).hasSize(1);
        assertThat(copied.limits()).containsKey("INR");
        List<BeneficialOwner> duplicate = List.of(new BeneficialOwner("First", "BCDEF2345G", 4000),
                new BeneficialOwner("Same owner", "BCDEF2345G", 4000));
        assertThatThrownBy(() -> new KycDetails(valid.legalName(), valid.pan(), valid.gstin(), valid.bankAccount(), duplicate,
                valid.risk(), valid.verification(), valid.limits())).isInstanceOf(GatewayException.class);
        List<BeneficialOwner> excessive = List.of(new BeneficialOwner("First", "BCDEF2345G", 6000),
                new BeneficialOwner("Second", "CDEFG3456H", 4001));
        assertThatThrownBy(() -> new KycDetails(valid.legalName(), valid.pan(), valid.gstin(), valid.bankAccount(), excessive,
                valid.risk(), valid.verification(), valid.limits())).isInstanceOf(GatewayException.class);
    }
}