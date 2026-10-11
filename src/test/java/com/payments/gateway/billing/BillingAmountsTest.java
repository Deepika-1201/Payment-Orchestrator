package com.payments.gateway.billing;

import com.payments.gateway.billing.BillingAmounts.Price;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingAmountsTest {

    @Test
    void servicePricesUseIntegerPaiseAndRoundHalfUp() {
        assertThat(new Price(17, 125).charge(12_340)).isEqualTo(171);
        assertThat(new Price(0, 5000).charge(1)).isEqualTo(1);
        assertThat(new Price(0, 10_000).charge(9_007_199_254_740_993L)).isEqualTo(9_007_199_254_740_993L);
        assertThatThrownBy(() -> new Price(1, 10_000).charge(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new Price(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Price(0, 10_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invoiceTaxIsComputedOnTheSubtotalAndAllocatedWithoutLosingPaise() {
        assertThat(BillingAmounts.tax(171)).isEqualTo(31);
        assertThat(BillingAmounts.tax(25)).isEqualTo(5);
        assertThat(BillingAmounts.allocateTax(3, 1, 0, 1)).isZero();
        assertThat(BillingAmounts.allocateTax(3, 1, 1, 1)).isEqualTo(1);
        assertThat(BillingAmounts.allocateTax(3, 1, 2, 1)).isZero();
        assertThat(BillingAmounts.allocateTax(0, 0, 0, 0)).isZero();
    }

    @Test
    void partialCreditsReverseExactlyTheOriginalTaxAndCannotOverCredit() {
        long tax = 0;
        for (int previous = 0; previous < 101; previous++) {
            tax += BillingAmounts.allocateTax(101, 18, previous, 1);
        }
        assertThat(tax).isEqualTo(18);
        assertThatThrownBy(() -> BillingAmounts.allocateTax(101, 18, 100, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingAmounts.allocateTax(101, 18, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingAmounts.allocateTax(0, 1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void financialYearsChangeAtAprilMidnightInIndiaAndSeriesHaveBoundedNumbers() {
        assertThat(BillingAmounts.financialYear(Instant.parse("2027-03-31T18:29:59.999999Z"))).isEqualTo(2026);
        assertThat(BillingAmounts.financialYear(Instant.parse("2027-03-31T18:30:00Z"))).isEqualTo(2027);
        assertThat(BillingAmounts.number("INV", 2026, 1)).isEqualTo("INV/2026/000001");
        assertThat(BillingAmounts.number("CN", 2026, 1)).isEqualTo("CN/2026/000001");
        assertThat(BillingAmounts.number("INV", 2026, BillingAmounts.MAX_SEQUENCE)).hasSize(16);
        assertThatThrownBy(() -> BillingAmounts.number("INV", 2026, BillingAmounts.MAX_SEQUENCE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}