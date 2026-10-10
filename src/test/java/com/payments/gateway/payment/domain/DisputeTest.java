package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** When a dispute takes a response, and when its evidence is due (ADR-039). */
class DisputeTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
    private static final Duration NOTICE = Duration.ofDays(3);

    private static Dispute dispute(DisputeStatus status, Instant respondBy, MerchantResponse.Status responseStatus) {
        MerchantResponse response = responseStatus == null ? null : new MerchantResponse(MerchantResponse.Type.CONTEST,
                responseStatus, "Delivered.", List.of("dsf_1"), T0, null, null, 1, null);
        return Dispute.rehydrate(new DisputeSnapshot("dsp_1", "pay_1", "att_1", "mer_1", "MOCK_ALPHA", "psp_1",
                Money.of(10_000, "INR"), "fraudulent", status, respondBy, Review.NONE, 0, T0, T0, response, null));
    }

    @Test
    void aDisputeTakesAResponseWhileOpenBeforeItsDeadlineWithNothingPendingOrSent() {
        Instant later = T0.plusSeconds(60);
        assertThat(dispute(DisputeStatus.OPEN, later, null).openForResponse(T0)).isTrue();
        assertThat(dispute(DisputeStatus.OPEN, null, null).openForResponse(T0)).as("no deadline known").isTrue();
        assertThat(dispute(DisputeStatus.OPEN, T0, null).openForResponse(T0)).as("at the deadline").isFalse();
        assertThat(dispute(DisputeStatus.UNDER_REVIEW, later, null).openForResponse(T0)).isFalse();
        assertThat(dispute(DisputeStatus.OPEN, later, MerchantResponse.Status.PENDING).openForResponse(T0)).isFalse();
        assertThat(dispute(DisputeStatus.OPEN, later, MerchantResponse.Status.SENT).openForResponse(T0)).isFalse();
        assertThat(dispute(DisputeStatus.OPEN, later, MerchantResponse.Status.FAILED).openForResponse(T0))
                .as("a failed response can be replaced").isTrue();
        assertThatThrownBy(() -> dispute(DisputeStatus.OPEN, T0, null)
                .requestResponse(MerchantResponse.Type.ACCEPT, null, null, T0, T0))
                .isInstanceOf(GatewayException.class).hasMessageContaining("deadline");
    }

    @Test
    void evidenceIsDueWithinTheNoticeWindowWhileUnansweredAndNoticedOnce() {
        Instant deadline = T0.plus(NOTICE);
        assertThat(dispute(DisputeStatus.OPEN, deadline, null).evidenceDue(T0, NOTICE)).as("at the window's edge").isTrue();
        assertThat(dispute(DisputeStatus.OPEN, deadline.plusSeconds(1), null).evidenceDue(T0, NOTICE)).isFalse();
        assertThat(dispute(DisputeStatus.OPEN, T0.minusSeconds(1), null).evidenceDue(T0, NOTICE)).as("overdue").isTrue();
        assertThat(dispute(DisputeStatus.OPEN, null, null).evidenceDue(T0, NOTICE)).isFalse();
        assertThat(dispute(DisputeStatus.LOST, deadline, null).evidenceDue(T0, NOTICE)).isFalse();
        assertThat(dispute(DisputeStatus.OPEN, deadline, MerchantResponse.Status.PENDING).evidenceDue(T0, NOTICE)).isFalse();
        assertThat(dispute(DisputeStatus.OPEN, deadline, MerchantResponse.Status.SENT).evidenceDue(T0, NOTICE)).isFalse();
        assertThat(dispute(DisputeStatus.OPEN, deadline, MerchantResponse.Status.FAILED).evidenceDue(T0, NOTICE)).isTrue();

        Dispute due = dispute(DisputeStatus.OPEN, deadline, null);
        assertThat(due.notifyEvidenceDue(T0, NOTICE)).isTrue();
        assertThat(due.evidenceDueNotifiedAt()).isEqualTo(T0);
        assertThat(due.pullEvents()).extracting(PaymentEvent::type).containsExactly(PaymentEvent.Type.DISPUTE_EVIDENCE_DUE);
        assertThat(due.notifyEvidenceDue(T0, NOTICE)).as("once per dispute").isFalse();
        assertThat(due.pullEvents()).isEmpty();
        assertThat(dispute(DisputeStatus.OPEN, deadline.plusSeconds(1), null).notifyEvidenceDue(T0, NOTICE)).isFalse();
    }

    @Test
    void aResponseIsDeliveredRetriedOrFailedOnlyWhilePending() {
        Dispute dispute = dispute(DisputeStatus.OPEN, T0.plus(NOTICE), null);
        dispute.requestResponse(MerchantResponse.Type.CONTEST, "Delivered.", List.of("dsf_1"), T0.plusSeconds(120), T0);
        assertThat(dispute.response().nextAttemptAt()).isEqualTo(T0.plusSeconds(120));

        dispute.retryResponseAt(T0.plusSeconds(60), T0);
        assertThat(dispute.response().attempts()).isEqualTo(1);
        dispute.responseFailed("evidence_rejected", T0);

        assertThat(dispute.response().status()).isEqualTo(MerchantResponse.Status.FAILED);
        assertThat(dispute.response().failure()).isEqualTo("evidence_rejected");
        assertThat(dispute.response().nextAttemptAt()).isNull();
        assertThat(dispute.review().reasonList()).containsExactly(Review.RESPONSE_FAILED);
        assertThat(dispute.pullEvents()).extracting(PaymentEvent::type)
                .containsExactly(PaymentEvent.Type.DISPUTE_RESPONSE_FAILED);
        assertThatThrownBy(() -> dispute.responseSent(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> dispute.retryResponseAt(T0, T0)).isInstanceOf(IllegalStateException.class);

        dispute.requestResponse(MerchantResponse.Type.ACCEPT, null, null, T0, T0);
        dispute.responseSent(T0.plusSeconds(5));
        assertThat(dispute.response().status()).isEqualTo(MerchantResponse.Status.SENT);
        assertThat(dispute.response().sentAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(dispute.response().attempts()).as("counted per response").isEqualTo(1);
        assertThatThrownBy(() -> dispute.responseFailed("late", T0)).isInstanceOf(IllegalStateException.class);
    }
}
