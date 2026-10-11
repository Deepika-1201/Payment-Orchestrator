package com.payments.gateway.treasury;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCalendarTest {
    @Test
    void skipsWeekendsAndConfiguredBankHolidays() {
        var calendar = new SettlementCalendar(new TreasuryProperties(Set.of(LocalDate.parse("2026-10-12")), 100));
        Instant friday = Instant.parse("2026-10-09T12:00:00Z");
        assertThat(calendar.eligibleAt(friday, 1)).isEqualTo(Instant.parse("2026-10-12T18:30:00Z"));
        assertThat(calendar.eligibleAt(friday, 2)).isEqualTo(Instant.parse("2026-10-13T18:30:00Z"));
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-10-10"))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-10-11"))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-10-12"))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-10-13"))).isTrue();
    }

    @Test
    void usesTheIndiaDateAndRejectsInvalidCycles() {
        var calendar = new SettlementCalendar(new TreasuryProperties(null, 0));
        assertThat(calendar.eligibleAt(Instant.parse("2026-10-07T20:00:00Z"), 1))
                .isEqualTo(Instant.parse("2026-10-08T18:30:00Z"));
        assertThatThrownBy(() -> calendar.eligibleAt(Instant.now(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.eligibleAt(Instant.now(), 31)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new TreasuryProperties(null, 0).batchSize()).isEqualTo(100);
        assertThatThrownBy(() -> new TreasuryProperties(null, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TreasuryProperties(null, 1001)).isInstanceOf(IllegalArgumentException.class);
    }
}