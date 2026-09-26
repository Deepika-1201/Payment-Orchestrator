package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundTest {

    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");

    private static Refund newRefund() {
        return Refund.initiate("rfnd_1", "pay_1", "att_1", "mer_1", "PSP_A", Money.of(1_000, "INR"), "customer request",
                null, RefundInitiator.MERCHANT, T0);
    }

    @Test
    void progressesMonotonically() {
        Refund refund = newRefund();

        assertThat(refund.apply(RefundStatus.PENDING, "psp_rfnd", null, TransitionSource.PROVIDER_RESPONSE, T0)).isEqualTo(TransitionOutcome.APPLIED);
        assertThat(refund.apply(RefundStatus.UNKNOWN, null, null, TransitionSource.PROVIDER_RESPONSE, T0)).isEqualTo(TransitionOutcome.NO_OP);
        assertThat(refund.apply(RefundStatus.SUCCEEDED, null, null, TransitionSource.PROVIDER_WEBHOOK, T0)).isEqualTo(TransitionOutcome.APPLIED);
        assertThat(refund.apply(RefundStatus.SUCCEEDED, null, null, TransitionSource.PROVIDER_WEBHOOK, T0)).isEqualTo(TransitionOutcome.NO_OP);
        assertThat(refund.providerReference()).isEqualTo("psp_rfnd");
        assertThat(refund.pullChanges()).extracting(StatusChange::toStatus).containsExactly("INITIATED", "PENDING", "SUCCEEDED");
    }

    @Test
    void succeededRefundCannotBecomeFailed() {
        Refund refund = newRefund();
        refund.apply(RefundStatus.SUCCEEDED, null, null, TransitionSource.PROVIDER_RESPONSE, T0);

        TransitionOutcome outcome = refund.apply(RefundStatus.FAILED, null,
                new Failure("refund_failed", FailureCategory.PROVIDER, "x"), TransitionSource.PROVIDER_WEBHOOK, T0);

        assertThat(outcome).isEqualTo(TransitionOutcome.CONFLICT);
        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.needsReview()).isTrue();
    }
}
