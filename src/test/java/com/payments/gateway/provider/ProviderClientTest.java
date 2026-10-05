package com.payments.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.provider.spi.SettlementReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ProviderClientTest {

    private static final MerchantAccountResolver ACCOUNTS = new MerchantAccountResolver() {
        @Override
        public MerchantAccount require(String merchantId, String providerCode) {
            return new MerchantAccount("mpa_1", merchantId, providerCode, Map.of());
        }

        @Override
        public Optional<MerchantAccount> findById(String accountId) {
            return Optional.empty();
        }
    };

    @Test
    void anOpenCircuitTurnsHalfOpenOnItsOwnSoAProviderIsTriedAgainAfterAnOutage() throws InterruptedException {
        AtomicBoolean down = new AtomicBoolean(true);
        PaymentProvider psp = mock(PaymentProvider.class);
        when(psp.code()).thenReturn("ALPHA");
        when(psp.initiatePayment(any(), any())).thenAnswer(invocation -> {
            if (down.get()) {
                throw new ProviderUnavailableException("ALPHA", "outage");
            }
            return ProviderPaymentResult.pending("ref_1", "pending");
        });
        @SuppressWarnings("unchecked")
        ObjectProvider<PaymentProvider> beans = mock(ObjectProvider.class);
        when(beans.orderedStream()).thenAnswer(invocation -> Stream.of(psp));
        ProviderClient client = new ProviderClient(new ProviderRegistry(beans), new ProviderHealthTracker(),
                new SimpleMeterRegistry(), ACCOUNTS, Duration.ofMillis(200));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> client.initiate("mer_1", "ALPHA", null)).isInstanceOf(ProviderUnavailableException.class);
        }
        assertThat(client.isAvailable("ALPHA")).isFalse();

        down.set(false);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!client.isAvailable("ALPHA") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(client.circuitState("ALPHA")).as("routing sends no calls to an open circuit").isEqualTo("HALF_OPEN");
        for (int i = 0; i < 3; i++) {
            client.initiate("mer_1", "ALPHA", null);
        }
        assertThat(client.circuitState("ALPHA")).isEqualTo("CLOSED");
    }

    @Test
    void slowOrFailingSettlementReportsNeverOpenTheCircuitOrSlowRoutingDown() {
        PaymentProvider psp = mock(PaymentProvider.class);
        when(psp.code()).thenReturn("ALPHA");
        AtomicBoolean fail = new AtomicBoolean(true);
        when(psp.fetchSettlementReport(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(50);
            if (fail.get()) {
                throw new IllegalStateException("page 3 is malformed");
            }
            return new SettlementReport(List.of(), List.of());
        });
        @SuppressWarnings("unchecked")
        ObjectProvider<PaymentProvider> beans = mock(ObjectProvider.class);
        when(beans.orderedStream()).thenAnswer(invocation -> Stream.of(psp));
        ProviderHealthTracker health = new ProviderHealthTracker();
        ProviderClient client = new ProviderClient(new ProviderRegistry(beans), health, new SimpleMeterRegistry(), ACCOUNTS,
                Duration.ofSeconds(30));
        SettlementReportQuery query = new SettlementReportQuery("mer_1", Instant.EPOCH, Instant.EPOCH.plusSeconds(86_400));

        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> client.fetchSettlementReport("ALPHA", query))
                    .isInstanceOf(ProviderUnavailableException.class).hasMessageContaining("page 3 is malformed");
        }
        fail.set(false);
        assertThat(client.fetchSettlementReport("ALPHA", query).lines()).isEmpty();

        assertThat(client.circuitState("ALPHA")).isEqualTo("CLOSED");
        assertThat(health.latencyMillis("ALPHA")).as("report latency is not payment latency").isZero();
    }
}
