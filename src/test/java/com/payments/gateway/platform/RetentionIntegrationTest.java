package com.payments.gateway.platform;

import com.payments.gateway.support.FakeMerchantEndpoint;
import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionIntegrationTest extends IntegrationTest {

    @Autowired
    private RetentionJob retention;

    @Test
    void expiredOperationalRowsArePurgedWhileFinancialRecordsAndUnfinishedWorkStay() {
        try (FakeMerchantEndpoint healthy = new FakeMerchantEndpoint(); FakeMerchantEndpoint failing = new FakeMerchantEndpoint()) {
            failing.respondWith(500);
            payAndSucceed(createMerchantWith(healthy.url(), null, ALPHA), 10_000);
            payAndSucceed(createMerchantWith(failing.url(), null, ALPHA), 20_000);
            deliveryWorker.deliverDue();
            jdbc.sql("""
                    INSERT INTO provider_webhook_events (id, provider_code, provider_event_id, event_type, payload,
                                                         normalized_event, status, received_at)
                    VALUES ('pwe_retrying', 'MOCK_ALPHA', 'evt_retrying', 'payment.updated', '{}', '{}'::jsonb, 'RECEIVED',
                            now() - interval '1 year')
                    """).update();
            long payments = count("SELECT count(*) FROM payments");
            long ledgerEntries = count("SELECT count(*) FROM ledger_entries");
            long auditEntries = count("SELECT count(*) FROM audit_log");

            clock.advance(Duration.ofDays(91));
            Map<String, Integer> first = retention.run();

            assertThat(first.get("idempotency_records")).isPositive();
            assertThat(count("SELECT count(*) FROM idempotency_records")).isZero();
            assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE status = 'SUCCEEDED'")).isZero();
            assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE status = 'PENDING'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM merchant_events")).as("the event with a pending delivery stays").isEqualTo(1);
            assertThat(count("SELECT count(*) FROM provider_webhook_events")).as("PSP webhooks are kept 180 days").isEqualTo(3);

            clock.advance(Duration.ofDays(90));
            retention.run();

            assertThat(jdbc.sql("SELECT id FROM provider_webhook_events").query(String.class).list())
                    .as("events still being retried are never purged").containsExactly("pwe_retrying");
            assertThat(count("SELECT count(*) FROM payments")).isEqualTo(payments);
            assertThat(count("SELECT count(*) FROM ledger_entries")).isEqualTo(ledgerEntries);
            assertThat(count("SELECT count(*) FROM audit_log")).isEqualTo(auditEntries);
        }
    }
}
