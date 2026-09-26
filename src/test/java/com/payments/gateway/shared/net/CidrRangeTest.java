package com.payments.gateway.shared.net;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CidrRangeTest {

    @Test
    void matchesAddressesInsideTheBlockOnly() {
        CidrRange range = CidrRange.parse("203.0.113.0/24");
        assertThat(range.contains("203.0.113.7")).isTrue();
        assertThat(range.contains("203.0.114.7")).isFalse();
        assertThat(CidrRange.parse("10.1.0.0/15").contains("10.0.255.1")).isTrue();
        assertThat(CidrRange.parse("10.1.0.0/15").contains("10.2.0.1")).isFalse();
        assertThat(CidrRange.parse("198.51.100.9").contains("198.51.100.9")).isTrue();
        assertThat(CidrRange.parse("2001:db8::/32").contains("2001:db8:1::5")).isTrue();
        assertThat(range.contains("2001:db8:1::5")).as("other family").isFalse();
        assertThat(range.contains("example.com")).as("never resolves names").isFalse();
    }

    @Test
    void rejectsInvalidBlocks() {
        assertThatThrownBy(() -> CidrRange.parse("203.0.113.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrRange.parse("psp.example.com/24")).isInstanceOf(IllegalArgumentException.class);
    }
}
