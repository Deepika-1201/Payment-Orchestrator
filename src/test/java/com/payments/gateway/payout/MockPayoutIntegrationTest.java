package com.payments.gateway.payout;

import com.payments.gateway.payout.mock.MockPayoutProvider;
import com.payments.gateway.payout.spi.PayoutProvider.Destination;
import com.payments.gateway.payout.spi.PayoutProvider.Instruction;
import com.payments.gateway.payout.spi.PayoutProvider.Outcome;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPayoutIntegrationTest extends IntegrationTest {
    @Autowired
    private MockPayoutProvider payouts;
    @Autowired
    private EscrowProperties escrow;

    @Test
    void remoteIdempotencySurvivesANewProviderInstanceAndRejectsChangedContent() {
        assertThat(escrow.requireCurrentStatement()).isTrue();
        var instruction = instruction("pout_test", 1000);
        assertThat(payouts.create(instruction).outcome()).isEqualTo(Outcome.SUCCEEDED);
        var restarted = new MockPayoutProvider(jdbc, new com.payments.gateway.shared.crypto.SecretCipher(
                java.util.Base64.getDecoder().decode("bG9jYWwtZGV2LWtleS0wMDAwMDAwMDAwMDAwMDAwMDA=")), json, clock, "secret");
        assertThat(restarted.fetch(instruction, null).providerReference()).isEqualTo("mock_pout_test");
        assertThat(restarted.create(instruction).providerReference()).isEqualTo("mock_pout_test");
        assertThat(count("SELECT count(*) FROM simulated_payouts")).isEqualTo(1);
        assertThatThrownBy(() -> restarted.create(instruction("pout_test", 1100))).hasMessageContaining("idempotency key reused");
    }

    @Test
    void distinguishesTimeoutBeforeAndAfterRemoteProcessing() {
        var processed = instruction("pout_processed", 1001);
        assertThat(payouts.create(processed).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(payouts.fetch(processed, null).outcome()).isEqualTo(Outcome.SUCCEEDED);
        var missing = instruction("pout_missing", 1005);
        assertThat(payouts.create(missing).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(payouts.fetch(missing, null).outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(payouts.createCalls(missing.id())).isZero();
    }

    private static Instruction instruction(String id, long amount) {
        return new Instruction(id, "merchant", "MOCK_ESCROW", Money.of(amount, "INR"),
                new Destination("Example Merchant", "123456789012", "HDFC0001234"));
    }
}