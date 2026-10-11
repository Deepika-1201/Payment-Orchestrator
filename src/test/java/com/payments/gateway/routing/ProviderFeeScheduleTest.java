package com.payments.gateway.routing;

import com.payments.gateway.routing.ProviderFeeSchedule.Collection;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderFeeScheduleTest {

    @Test
    void estimatesFixedAndPercentageFeesThenRoundsTaxSeparately() {
        ProviderFeeSchedule schedule = schedule("INR", 17, 125, 1800, Collection.WITHHELD);
        assertThat(schedule.estimate(Money.of(12_340, "INR"))).isEqualTo(Money.of(202, "INR"));
        assertThat(schedule("INR", 0, 5000, 1800, Collection.WITHHELD).estimate(Money.of(1, "INR")))
                .isEqualTo(Money.of(1, "INR"));
        assertThat(schedule("INR", 0, 10_000, 5000, Collection.WITHHELD).estimate(Money.of(1, "INR")))
                .isEqualTo(Money.of(2, "INR"));
    }

    @Test
    void postpaidFeesStillHaveRoutingCostButNoSettlementWithholding() {
        ProviderFeeSchedule schedule = schedule("INR", 17, 125, 1800, Collection.POSTPAID);
        assertThat(schedule.estimate(Money.of(12_340, "INR")).amount()).isEqualTo(202);
        assertThat(schedule.expectedWithheld(Money.of(12_340, "INR"), Money.of(12_340, "INR")).amount()).isZero();
    }

    @Test
    void foreignFeeComparisonUsesTheReportedSettlementAmounts() {
        ProviderFeeSchedule schedule = schedule("USD", 30, 200, 1800, Collection.WITHHELD);
        assertThat(schedule.estimate(Money.of(10_000, "USD"))).isEqualTo(Money.of(271, "USD"));
        assertThat(schedule.expectedWithheld(Money.of(10_000, "USD"), Money.of(832_500, "INR")))
                .isEqualTo(Money.of(22_595, "INR"));
    }

    @Test
    void estimatesDoNotLosePrecisionAboveDoubleIntegerRange() {
        ProviderFeeSchedule schedule = schedule("INR", 0, 10_000, 0, Collection.WITHHELD);
        assertThat(schedule.estimate(Money.of(9_007_199_254_740_993L, "INR")).amount()).isEqualTo(9_007_199_254_740_993L);
        assertThatThrownBy(() -> schedule("INR", 1, 10_000, 0, Collection.WITHHELD).estimate(Money.of(Long.MAX_VALUE, "INR")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsInvalidRatesAmountsAndCurrencies() {
        assertThatThrownBy(() -> schedule("INR", -1, 200, 1800, Collection.WITHHELD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule("INR", 0, -1, 1800, Collection.WITHHELD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule("INR", 0, 10_001, 1800, Collection.WITHHELD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule("INR", 0, 200, 10_001, Collection.WITHHELD)).isInstanceOf(IllegalArgumentException.class);
        ProviderFeeSchedule schedule = schedule("INR", 0, 200, 1800, Collection.WITHHELD);
        assertThatThrownBy(() -> schedule.estimate(Money.of(0, "INR"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.estimate(Money.of(100, "USD"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.expectedWithheld(Money.of(100, "INR"), Money.of(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProviderFeeSchedule schedule(String currency, long fixed, int rate, int tax, Collection collection) {
        return new ProviderFeeSchedule("fee_1", "mer_1", "PSP", MethodType.CARD, currency, fixed, rate, tax, collection,
                Instant.EPOCH, Instant.EPOCH);
    }
}