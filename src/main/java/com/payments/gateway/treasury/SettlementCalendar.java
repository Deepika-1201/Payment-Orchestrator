package com.payments.gateway.treasury;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class SettlementCalendar {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private final TreasuryProperties properties;

    public SettlementCalendar(TreasuryProperties properties) {
        this.properties = properties;
    }

    public boolean isBusinessDay(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
                && !properties.holidays().contains(day);
    }

    public Instant eligibleAt(Instant occurredAt, int businessDays) {
        if (businessDays < 1 || businessDays > 30) {
            throw new IllegalArgumentException("settlement delay must be 1-30 business days");
        }
        LocalDate day = occurredAt.atZone(ZONE).toLocalDate();
        int remaining = businessDays;
        while (remaining > 0) {
            day = day.plusDays(1);
            if (isBusinessDay(day)) {
                remaining--;
            }
        }
        return day.atStartOfDay(ZONE).toInstant();
    }
}