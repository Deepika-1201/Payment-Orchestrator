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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
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
        assertThat(attempt.snapshot().captureAmount()).as("captured without a capture request: the full amount")
                .isEqualTo(AMOUNT);
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

        private static final Predicate<PaymentAttempt> PARTIAL = attempt -> true;
        private static final Predicate<PaymentAttempt> NO_PARTIAL = attempt -> false;

        private Payment authorizedManualPayment() {
            Payment payment = newPayment(CaptureMethod.MANUAL);
            start(payment, "att_1");
            payment.applyAttemptUpdate("att_1", new AttemptUpdate(AttemptStatus.AUTHORIZED, "psp_ref", null, null, AMOUNT),
                    TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);
            return payment;
        }

        @Test
        void manualCaptureFlow() {
            Payment payment = newPayment(CaptureMethod.MANUAL);
            PaymentAttempt attempt = start(payment, "att_1");
            payment.applyAttemptUpdate("att_1", new AttemptUpdate(AttemptStatus.AUTHORIZED, "psp_ref", null, null, AMOUNT),
                    TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);
            assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(payment.authorizationExpiresAt()).isEqualTo(T0.plus(Duration.ofDays(5)));

            payment.requestCapture(null, NO_PARTIAL, T0);
            assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(attempt.status()).isEqualTo(AttemptStatus.CAPTURE_PENDING);
            assertThat(attempt.snapshot().captureAmount()).isEqualTo(AMOUNT);

            payment.applyAttemptUpdate("att_1", AttemptUpdate.of(AttemptStatus.AUTHORIZED), TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);
            assertThat(attempt.status()).as("stale authorized webhook must not undo the capture request")
                    .isEqualTo(AttemptStatus.CAPTURE_PENDING);

            payment.applyAttemptUpdate("att_1", succeeded(AMOUNT), TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);
            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.amountCaptured()).isEqualTo(49_900);
        }

        @Test
        void partialCaptureCapturesLessThanTheAuthorization() {
            Payment payment = authorizedManualPayment();
            PaymentAttempt attempt = payment.attempt("att_1").orElseThrow();
            Money partial = Money.of(30_000, "INR");

            payment.requestCapture(30_000L, PARTIAL, T0);
            assertThat(attempt.status()).isEqualTo(AttemptStatus.CAPTURE_PENDING);
            assertThat(attempt.capturedAmount()).isEqualTo(partial);
            AttemptApplyResult lateAuthorization = payment.applyAttemptUpdate("att_1",
                    new AttemptUpdate(AttemptStatus.AUTHORIZED, "psp_ref", null, null, AMOUNT),
                    TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);
            assertThat(lateAuthorization.kind()).as("an authorization still reports the authorized amount")
                    .isEqualTo(AttemptApplyResult.Kind.NO_OP);

            payment.applyAttemptUpdate("att_1", succeeded(partial), TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);

            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.amountCaptured()).isEqualTo(30_000);
            assertThat(attempt.needsReview()).isFalse();
        }

        @Test
        void successForTheFullAmountAfterAPartialCaptureRequestIsNotApplied() {
            Payment payment = authorizedManualPayment();
            payment.requestCapture(30_000L, PARTIAL, T0);

            AttemptApplyResult result = payment.applyAttemptUpdate("att_1", succeeded(AMOUNT),
                    TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);

            assertThat(result.kind()).isEqualTo(AttemptApplyResult.Kind.AMOUNT_MISMATCH);
            assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        }

        @Test
        void captureAboveTheAuthorizationOrBelowOneIsRefused() {
            Payment payment = authorizedManualPayment();

            for (long amount : new long[] {49_901, 0}) {
                assertThatThrownBy(() -> payment.requestCapture(amount, PARTIAL, T0))
                        .isInstanceOf(GatewayException.class)
                        .extracting(e -> ((GatewayException) e).code())
                        .isEqualTo(ErrorCode.CAPTURE_AMOUNT_MISMATCH);
            }
            assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        }

        @Test
        void captureOfOneUnitIsAllowed() {
            Payment payment = authorizedManualPayment();

            payment.requestCapture(1L, PARTIAL, T0);

            assertThat(payment.attempt("att_1").orElseThrow().capturedAmount()).isEqualTo(Money.of(1, "INR"));
        }

        @Test
        void partialCaptureNeedsAPspThatReleasesTheRest() {
            Payment payment = authorizedManualPayment();
            List<String> asked = new ArrayList<>();

            assertThatThrownBy(() -> payment.requestCapture(30_000L, attempt -> {
                asked.add(attempt.id());
                return false;
            }, T0))
                    .isInstanceOf(GatewayException.class)
                    .extracting(e -> ((GatewayException) e).code())
                    .isEqualTo(ErrorCode.UNSUPPORTED_PAYMENT_METHOD);
            assertThat(asked).containsExactly("att_1");
            assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        }

        @Test
        void captureOfTheAuthorizedAmountIsAFullCapture() {
            Payment payment = authorizedManualPayment();

            payment.requestCapture(49_900L, attempt -> {
                throw new AssertionError("a full capture needs no partial-capture support");
            }, T0);

            assertThat(payment.attempt("att_1").orElseThrow().capturedAmount()).isEqualTo(AMOUNT);
        }

        @Test
        void rejectedCaptureClearsTheAmountSoAnotherCanBeRequested() {
            Payment payment = authorizedManualPayment();
            PaymentAttempt attempt = payment.attempt("att_1").orElseThrow();
            payment.requestCapture(30_000L, PARTIAL, T0);

            payment.rejectCapture("att_1", new Failure("amount_refused", FailureCategory.VALIDATION, "Refused"), T0);
            assertThat(attempt.status()).isEqualTo(AttemptStatus.AUTHORIZED);
            assertThat(attempt.snapshot().captureAmount()).isNull();
            assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);

            payment.requestCapture(20_000L, PARTIAL, T0);
            assertThat(attempt.capturedAmount()).isEqualTo(Money.of(20_000, "INR"));
        }

        @Test
        void lateAuthorizationOfAnotherAttemptDuringACaptureIsVoided() {
            Payment payment = newPayment(CaptureMethod.MANUAL);
            PaymentAttempt first = start(payment, "att_1");
            payment.applyAttemptUpdate("att_1", failed(), TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);
            start(payment, "att_2");
            payment.applyAttemptUpdate("att_2", AttemptUpdate.of(AttemptStatus.AUTHORIZED), TransitionSource.PROVIDER_WEBHOOK,
                    AUTO_REFUND, T0);
            payment.requestCapture(30_000L, PARTIAL, T0);
            payment.pullEvents();

            AttemptApplyResult result = payment.applyAttemptUpdate("att_1", AttemptUpdate.of(AttemptStatus.AUTHORIZED),
                    TransitionSource.PROVIDER_WEBHOOK, AUTO_REFUND, T0);

            assertThat(result.voidRequired()).isTrue();
            assertThat(first.voidRequested()).isTrue();
            assertThat(payment.status()).as("the capture of att_2 is still in flight").isEqualTo(PaymentStatus.PROCESSING);
            assertThat(payment.pullEvents()).isEmpty();
        }

        @Test
        void authorizationForAutomaticCaptureTriggersCapture() {
            Payment payment = newPayment(CaptureMethod.AUTOMATIC);
            PaymentAttempt attempt = start(payment, "att_1");

            AttemptApplyResult result = payment.applyAttemptUpdate("att_1", AttemptUpdate.of(AttemptStatus.AUTHORIZED),
                    TransitionSource.PROVIDER_RESPONSE, AUTO_REFUND, T0);

            assertThat(result.captureRequired()).isTrue();
            assertThat(attempt.status()).isEqualTo(AttemptStatus.CAPTURE_PENDING);
            assertThat(attempt.snapshot().captureAmount()).isEqualTo(AMOUNT);
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

    /** Credit allocation (ADR-038, LLD §21.2). */
    @Nested
    class BankTransfers {

        private static final PaymentPolicy ADD_UP = new PaymentPolicy(3, Duration.ofDays(5), false, true, false);
        private static final PaymentPolicy EXACT = new PaymentPolicy(3, Duration.ofDays(5), false, false, false);
        private static final TransitionSource WEBHOOK = TransitionSource.PROVIDER_WEBHOOK;

        private Payment waiting(PaymentMethod method) {
            Payment payment = newPayment(CaptureMethod.AUTOMATIC);
            payment.startAttempt("att_1", method, "PSP_A", null, 3, T0);
            payment.applyAttemptUpdate("att_1", new AttemptUpdate(AttemptStatus.REQUIRES_ACTION, "va_1",
                    NextAction.redirect("https://psp.example/va_1"), null, null), TransitionSource.PROVIDER_RESPONSE,
                    ADD_UP, T0);
            return payment;
        }

        @Test
        void creditsAddUpToExactlyTheAmountAndTheRestGoesBack() {
            Payment payment = waiting(PaymentMethod.bankTransfer());

            CreditAllocation first = payment.allocateCredit("att_1", Money.of(30_000, "INR"), 0, ADD_UP, WEBHOOK, T0);
            CreditAllocation second = payment.allocateCredit("att_1", Money.of(20_000, "INR"), 30_000, ADD_UP, WEBHOOK, T0);

            assertThat(first.applied()).isEqualTo(30_000);
            assertThat(first.returned()).isZero();
            assertThat(first.funded()).isFalse();
            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(second.applied()).isEqualTo(19_900);
            assertThat(second.returned()).isEqualTo(100);
            assertThat(second.returnReason()).isEqualTo(CreditAllocation.EXCESS);
            assertThat(second.funded()).isTrue();
            assertThat(payment.amountCaptured()).isEqualTo(49_900);
        }

        @Test
        void exactOnlyTakesACreditOfTheWholeAmount() {
            Payment payment = waiting(PaymentMethod.bankTransfer());

            for (long inexact : new long[] {49_899, 49_901}) {
                CreditAllocation refused = payment.allocateCredit("att_1", Money.of(inexact, "INR"), 0, EXACT, WEBHOOK, T0);
                assertThat(refused.applied()).isZero();
                assertThat(refused.returned()).isEqualTo(inexact);
                assertThat(refused.returnReason()).isEqualTo(CreditAllocation.INEXACT);
            }
            assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
            assertThat(payment.allocateCredit("att_1", AMOUNT, 0, EXACT, WEBHOOK, T0).funded()).isTrue();
        }

        @Test
        void creditsAtOrAfterExpiryInAnotherCurrencyOrAfterSuccessGoBackAsLate() {
            Payment payment = waiting(PaymentMethod.bankTransfer());
            Instant expiry = T0.plus(Duration.ofMinutes(15));

            CreditAllocation atExpiry = payment.allocateCredit("att_1", AMOUNT, 0, ADD_UP, WEBHOOK, expiry);
            CreditAllocation dollars = payment.allocateCredit("att_1", Money.of(49_900, "USD"), 0, ADD_UP, WEBHOOK, T0);
            CreditAllocation beforeExpiry = payment.allocateCredit("att_1", AMOUNT, 0, ADD_UP, WEBHOOK,
                    expiry.minusSeconds(1));
            CreditAllocation afterSuccess = payment.allocateCredit("att_1", AMOUNT, 49_900, ADD_UP, WEBHOOK, T0);

            for (CreditAllocation late : List.of(atExpiry, dollars, afterSuccess)) {
                assertThat(late.applied()).isZero();
                assertThat(late.returned()).isEqualTo(49_900);
                assertThat(late.returnReason()).isEqualTo(CreditAllocation.LATE);
            }
            assertThat(beforeExpiry.funded()).isTrue();
        }

        @Test
        void aShortTransferIsAcceptedOnlyForLessThanTheAmountOnAWaitingTransfer() {
            Payment upi = waiting(PaymentMethod.upi(UpiFlow.INTENT, null));
            assertThatThrownBy(() -> upi.acceptShortTransfer("att_1", 40_000, ADD_UP, T0))
                    .isInstanceOf(IllegalStateException.class);
            Payment payment = waiting(PaymentMethod.bankTransfer());
            assertThatThrownBy(() -> payment.acceptShortTransfer("att_1", 0, ADD_UP, T0))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> payment.acceptShortTransfer("att_1", 49_900, ADD_UP, T0))
                    .isInstanceOf(IllegalStateException.class);

            payment.acceptShortTransfer("att_1", 1, ADD_UP, T0);

            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.amountCaptured()).isEqualTo(1);
            assertThat(payment.attempt("att_1").orElseThrow().capturedAmount()).isEqualTo(Money.of(1, "INR"));
        }
    }
}
