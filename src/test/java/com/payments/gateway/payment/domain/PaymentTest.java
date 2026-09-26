package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");
    private static final Money AMOUNT = Money.of(49_900, "INR");
    private static final PaymentPolicy AUTO_REFUND = new PaymentPolicy(3, Duration.ofDays(5), false);
    private static final PaymentPolicy ACCEPT = new PaymentPolicy(3, Duration.ofDays(5), true);

    private static Payment newPayment(CaptureMethod captureMethod) {
        return Payment.create("pay_1", "mer_1", "order_1", AMOUNT, captureMethod, null, Customer.NONE, Map.of(),
                T0.plus(Duration.ofMinutes(15)), T0);
    }

    private static PaymentAttempt start(Payment payment, String attemptId) {
        return payment.startAttempt(attemptId, PaymentMethod.upi(UpiFlow.INTENT, null), "PSP_A", null, 3, T0);
    }

    private static AttemptUpdate succeeded(Money amount) {
        return new AttemptUpdate(AttemptStatus.SUCCEEDED, "psp_ref", null, null, amount);
    }

    private static AttemptUpdate failed() {
        return AttemptUpdate.failed("declined", FailureCategory.ISSUER, "Declined");
    }

    @Test
    void createdPaymentRequiresPaymentMethod() {
        Payment payment = newPayment(CaptureMethod.AUTOMATIC);

        assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(payment.pullChanges()).extracting(StatusChange::toStatus).containsExactly("REQUIRES_PAYMENT_METHOD");
    }

    @Test
    void happyPathUpiIntentReachesSucceededAndEmitsEvent() {
        Payment payment = newPayment(CaptureMethod.AUTOMATIC);
        PaymentAttempt attempt = start(payment, "att_1");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);

        payment.applyAttemptUpdate(attempt.id(), new AttemptUpdate(AttemptStatus.REQUIRES_ACTION, "psp_ref",
                NextAction.upiIntent("upi://pay?x"), null, null), TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(attempt.nextAction().upiUri()).isEqualTo("upi://pay?x");

        AttemptApplyResult result = payment.applyAttemptUpdate(attempt.id(), succeeded(AMOUNT),
                TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0.plusSeconds(20));

        assertThat(result.kind()).isEqualTo(AttemptApplyResult.Kind.APPLIED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.amountCaptured()).isEqualTo(49_900);
        assertThat(payment.succeededAttemptId()).isEqualTo("att_1");
        assertThat(attempt.nextStatusCheckAt()).isNull();
        assertThat(payment.pullEvents()).extracting(PaymentEvent::type).containsExactly(PaymentEvent.Type.PAYMENT_SUCCEEDED);
    }

    @Test
    void duplicateAndStaleEvidenceAreNoOps() {
        Payment payment = newPayment(CaptureMethod.AUTOMATIC);
        PaymentAttempt attempt = start(payment, "att_1");
        payment.applyAttemptUpdate(attempt.id(), succeeded(AMOUNT), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);
        payment.pullEvents();

        assertThat(payment.applyAttemptUpdate(attempt.id(), succeeded(AMOUNT), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0).kind())
                .isEqualTo(AttemptApplyResult.Kind.NO_OP);
        assertThat(payment.applyAttemptUpdate(attempt.id(), AttemptUpdate.of(AttemptStatus.PENDING), TransitionSource.STATUS_CHECK, AUTO_REFUND, T0).kind())
                .isEqualTo(AttemptApplyResult.Kind.NO_OP);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.pullEvents()).isEmpty();
    }

    @Test
    void contradictingEvidenceIsFlaggedNotApplied() {
        Payment payment = newPayment(CaptureMethod.AUTOMATIC);
        PaymentAttempt attempt = start(payment, "att_1");
        payment.applyAttemptUpdate(attempt.id(), succeeded(AMOUNT), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);

        AttemptApplyResult result = payment.applyAttemptUpdate(attempt.id(), failed(), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);

        assertThat(result.kind()).isEqualTo(AttemptApplyResult.Kind.CONFLICT);
        assertThat(attempt.status()).isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(attempt.needsReview()).isTrue();
    }

    @Test
    void successWithWrongAmountIsNotApplied() {
        Payment payment = newPayment(CaptureMethod.AUTOMATIC);
        PaymentAttempt attempt = start(payment, "att_1");

        AttemptApplyResult result = payment.applyAttemptUpdate(attempt.id(), succeeded(Money.of(100, "INR")),
                TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);

        assertThat(result.kind()).isEqualTo(AttemptApplyResult.Kind.AMOUNT_MISMATCH);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(attempt.needsReview()).isTrue();
    }

    @Test
    void failedAttemptAllowsRetryUntilMaxAttempts() {
        Payment payment = newPayment(CaptureMethod.AUTOMATIC);
        for (int i = 1; i <= 3; i++) {
            PaymentAttempt attempt = start(payment, "att_" + i);
            payment.applyAttemptUpdate(attempt.id(), failed(), TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);
        }

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.failureCode()).isEqualTo("declined");
        assertThat(payment.pullEvents()).extracting(PaymentEvent::type).containsExactly(
                PaymentEvent.Type.PAYMENT_ATTEMPT_FAILED, PaymentEvent.Type.PAYMENT_ATTEMPT_FAILED, PaymentEvent.Type.PAYMENT_FAILED);
    }

    @Test
    void failedCannotBeOverturnedByNonProviderSource() {
        Payment payment = newPayment(CaptureMethod.AUTOMATIC);
        PaymentAttempt attempt = start(payment, "att_1");
        payment.applyAttemptUpdate(attempt.id(), failed(), TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);

        AttemptApplyResult result = payment.applyAttemptUpdate(attempt.id(), succeeded(AMOUNT), TransitionSource.SYSTEM, AUTO_REFUND, T0);

        assertThat(result.kind()).isEqualTo(AttemptApplyResult.Kind.CONFLICT);
        assertThat(attempt.status()).isEqualTo(AttemptStatus.FAILED);
    }

    @Nested
    class LateAndDuplicateSuccess {

        private Payment expiredWithPendingAttempt(PaymentAttempt[] holder) {
            Payment payment = newPayment(CaptureMethod.AUTOMATIC);
            holder[0] = start(payment, "att_1");
            payment.applyAttemptUpdate("att_1", new AttemptUpdate(AttemptStatus.REQUIRES_ACTION, "psp_ref", null, null, null),
                    TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);
            assertThat(payment.expire(T0.plus(Duration.ofMinutes(16)), Duration.ofMinutes(30)).changed()).isTrue();
            return payment;
        }

        @Test
        void lateSuccessIsRefundedByDefault() {
            PaymentAttempt[] attempt = new PaymentAttempt[1];
            Payment payment = expiredWithPendingAttempt(attempt);

            AttemptApplyResult result = payment.applyAttemptUpdate("att_1", succeeded(AMOUNT), TransitionSource.PROVIDER_WEBHOOK,
                    AUTO_REFUND, T0.plus(Duration.ofMinutes(20)));

            assertThat(result.refundInitiator()).isEqualTo(RefundInitiator.SYSTEM_LATE_SUCCESS);
            assertThat(payment.status()).isEqualTo(PaymentStatus.EXPIRED);
            assertThat(attempt[0].status()).isEqualTo(AttemptStatus.SUCCEEDED);
        }

        @Test
        void lateSuccessIsAcceptedWhenMerchantOptsIn() {
            PaymentAttempt[] attempt = new PaymentAttempt[1];
            Payment payment = expiredWithPendingAttempt(attempt);

            AttemptApplyResult result = payment.applyAttemptUpdate("att_1", succeeded(AMOUNT), TransitionSource.PROVIDER_WEBHOOK,
                    ACCEPT, T0.plus(Duration.ofMinutes(20)));

            assertThat(result.lateSuccessAccepted()).isTrue();
            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        }

        @Test
        void successAfterMerchantCancelIsAlwaysRefunded() {
            Payment payment = newPayment(CaptureMethod.AUTOMATIC);
            start(payment, "att_1");
            payment.applyAttemptUpdate("att_1", new AttemptUpdate(AttemptStatus.REQUIRES_ACTION, "psp_ref", null, null, null),
                    TransitionSource.PROVIDER_RESPONSE, ACCEPT, T0);
            payment.cancel("abandoned", T0);

            AttemptApplyResult result = payment.applyAttemptUpdate("att_1", succeeded(AMOUNT), TransitionSource.PROVIDER_WEBHOOK, ACCEPT, T0);

            assertThat(result.refundInitiator()).isEqualTo(RefundInitiator.SYSTEM_LATE_SUCCESS);
            assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
        }

        @Test
        void secondSuccessfulAttemptIsRefundedAsDuplicate() {
            Payment payment = newPayment(CaptureMethod.AUTOMATIC);
            start(payment, "att_1");
            payment.applyAttemptUpdate("att_1", failed(), TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);
            start(payment, "att_2");
            payment.applyAttemptUpdate("att_2", succeeded(AMOUNT), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);

            AttemptApplyResult result = payment.applyAttemptUpdate("att_1", succeeded(AMOUNT), TransitionSource.RECONCILIATION, AUTO_REFUND, T0);

            assertThat(result.refundInitiator()).isEqualTo(RefundInitiator.SYSTEM_DUPLICATE_SUCCESS);
            assertThat(payment.succeededAttemptId()).isEqualTo("att_2");
        }
    }

    @Nested
    class Capture {

        @Test
        void manualCaptureFlow() {
            Payment payment = newPayment(CaptureMethod.MANUAL);
            PaymentAttempt attempt = start(payment, "att_1");
            payment.applyAttemptUpdate("att_1", new AttemptUpdate(AttemptStatus.AUTHORIZED, "psp_ref", null, null, AMOUNT),
                    TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);
            assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(payment.authorizationExpiresAt()).isEqualTo(T0.plus(Duration.ofDays(5)));

            payment.requestCapture(null, T0);
            assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(attempt.status()).isEqualTo(AttemptStatus.CAPTURE_PENDING);

            payment.applyAttemptUpdate("att_1", AttemptUpdate.of(AttemptStatus.AUTHORIZED), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);
            assertThat(attempt.status()).as("stale authorized webhook must not undo the capture request")
                    .isEqualTo(AttemptStatus.CAPTURE_PENDING);

            payment.applyAttemptUpdate("att_1", succeeded(AMOUNT), TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);
            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        }

        @Test
        void partialCaptureIsRejected() {
            Payment payment = newPayment(CaptureMethod.MANUAL);
            start(payment, "att_1");
            payment.applyAttemptUpdate("att_1", AttemptUpdate.of(AttemptStatus.AUTHORIZED), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);

            assertThatThrownBy(() -> payment.requestCapture(100L, T0))
                    .isInstanceOf(GatewayException.class)
                    .extracting(e -> ((GatewayException) e).code())
                    .isEqualTo(ErrorCode.CAPTURE_AMOUNT_MISMATCH);
        }

        @Test
        void authorizationForAutomaticCaptureTriggersCapture() {
            Payment payment = newPayment(CaptureMethod.AUTOMATIC);
            PaymentAttempt attempt = start(payment, "att_1");

            AttemptApplyResult result = payment.applyAttemptUpdate("att_1", AttemptUpdate.of(AttemptStatus.AUTHORIZED),
                    TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);

            assertThat(result.captureRequired()).isTrue();
            assertThat(attempt.status()).isEqualTo(AttemptStatus.CAPTURE_PENDING);
            assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        }

        @Test
        void cancellingAnAuthorizedPaymentRequestsVoid() {
            Payment payment = newPayment(CaptureMethod.MANUAL);
            PaymentAttempt attempt = start(payment, "att_1");
            payment.applyAttemptUpdate("att_1", AttemptUpdate.of(AttemptStatus.AUTHORIZED), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);

            assertThat(payment.cancel("requested_by_customer", T0)).contains("att_1");
            assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(attempt.voidRequested()).isTrue();
        }
    }

    @Nested
    class Expiry {

        @Test
        void processingPaymentsGetAGracePeriod() {
            Payment payment = newPayment(CaptureMethod.AUTOMATIC);
            PaymentAttempt attempt = start(payment, "att_1");
            payment.applyAttemptUpdate(attempt.id(), AttemptUpdate.of(AttemptStatus.PENDING), TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);

            assertThat(payment.expire(T0.plus(Duration.ofMinutes(20)), Duration.ofMinutes(30)).changed()).isFalse();
            assertThat(payment.expire(T0.plus(Duration.ofMinutes(46)), Duration.ofMinutes(30)).changed()).isTrue();
            assertThat(payment.status()).isEqualTo(PaymentStatus.EXPIRED);
        }

        @Test
        void lapsedAuthorizationExpiresAndIsVoided() {
            Payment payment = newPayment(CaptureMethod.MANUAL);
            start(payment, "att_1");
            payment.applyAttemptUpdate("att_1", AttemptUpdate.of(AttemptStatus.AUTHORIZED), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);

            Payment.ExpireResult result = payment.expire(T0.plus(Duration.ofDays(6)), Duration.ofMinutes(30));

            assertThat(result.voidAttemptId()).isEqualTo("att_1");
            assertThat(payment.status()).isEqualTo(PaymentStatus.EXPIRED);
        }

        @Test
        void cannotConfirmOrCancelWhileProcessing() {
            Payment payment = newPayment(CaptureMethod.AUTOMATIC);
            start(payment, "att_1");

            assertThatThrownBy(() -> payment.cancel(null, T0)).isInstanceOf(GatewayException.class);
            assertThatThrownBy(() -> start(payment, "att_2")).isInstanceOf(GatewayException.class);
        }
    }

    @Test
    void refundsCannotExceedCapturedAmount() {
        Payment payment = newPayment(CaptureMethod.AUTOMATIC);
        start(payment, "att_1");
        payment.applyAttemptUpdate("att_1", succeeded(AMOUNT), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);

        payment.recordRefundSucceeded("att_1", 40_000, T0);

        assertThat(payment.amountRefunded()).isEqualTo(40_000);
        assertThatThrownBy(() -> payment.recordRefundSucceeded("att_1", 10_000, T0)).isInstanceOf(IllegalStateException.class);
    }
}
