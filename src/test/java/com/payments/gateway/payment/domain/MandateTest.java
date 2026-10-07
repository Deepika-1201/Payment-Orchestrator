package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.MandateFrequency;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MandateTest {

    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");
    private static final Instant END = T0.plus(Duration.ofDays(365));
    private static final Instant AUTHORIZE_BY = T0.plus(Duration.ofHours(24));
    private static final Money MAX = Money.of(5_000_000, "INR");
    private static final long FRICTIONLESS = 1_500_000;
    private static final Duration NOTIFY_AHEAD = Duration.ofHours(26);
    private static final Duration LEAD = Duration.ofHours(24);
    private static final Duration RETRY = Duration.ofHours(30);
    private static final Duration CHECK = Duration.ofMinutes(5);
    private static final Duration TIMEOUT = Duration.ofHours(48);

    private static Mandate newMandate(MandateInstrument instrument) {
        return Mandate.create("mdt_1", "mer_1", "PSP_A", instrument, MAX, MandateFrequency.MONTHLY, T0, END, "Gym",
                new MandateCustomer("cust_1", "Asha", "asha@example.com", "+919999999999"), Map.of(), AUTHORIZE_BY, T0);
    }

    private static Mandate active(MandateInstrument instrument) {
        Mandate mandate = newMandate(instrument);
        mandate.apply(new MandateUpdate(MandateStatus.ACTIVE, "reg_1", "tok_1", "cust_psp_1", null, null),
                TransitionSource.PROVIDER_WEBHOOK, T0);
        mandate.pullEvents();
        mandate.pullChanges();
        return mandate;
    }

    private static MandateDebit debit(Mandate mandate, long amount, Instant dueAt, Instant now) {
        return mandate.newDebit("mdd_1", "pay_1", "inv_1", Money.of(amount, "INR"), FRICTIONLESS, dueAt, "October",
                NOTIFY_AHEAD, now);
    }

    @Nested
    class Lifecycle {

        @Test
        void createdMandateIsCheckedSoonInCaseTheRegistrationResponseIsLost() {
            Mandate mandate = newMandate(MandateInstrument.UPI_AUTOPAY);

            assertThat(mandate.status()).isEqualTo(MandateStatus.CREATED);
            assertThat(mandate.nextCheckAt()).isEqualTo(T0.plusSeconds(30));
            assertThat(mandate.pullChanges()).extracting(StatusChange::toStatus).containsExactly("CREATED");
        }

        @Test
        void termMustEndAfterItStarts() {
            assertThatThrownBy(() -> Mandate.create("mdt_1", "mer_1", "PSP_A", MandateInstrument.CARD, MAX,
                    MandateFrequency.MONTHLY, T0, T0, null, new MandateCustomer(null, null, "a@example.com", "+919999999999"),
                    Map.of(), AUTHORIZE_BY, T0))
                    .isInstanceOf(GatewayException.class).hasMessageContaining("end_at");
        }

        @Test
        void authorizationActivatesOnceAndPauseResumeFollowTheCustomer() {
            Mandate mandate = newMandate(MandateInstrument.UPI_AUTOPAY);
            NextAction approve = NextAction.redirect("https://psp.example/approve");
            assertThat(mandate.apply(new MandateUpdate(MandateStatus.PENDING_AUTHORIZATION, "reg_1", null, "cust_psp_1",
                    approve, null), TransitionSource.PROVIDER_RESPONSE, T0)).isTrue();
            assertThat(mandate.nextAction()).isEqualTo(approve);
            assertThat(mandate.nextCheckAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));

            Instant activatedAt = T0.plus(Duration.ofMinutes(3));
            mandate.apply(new MandateUpdate(MandateStatus.ACTIVE, null, "tok_1", null, null, null),
                    TransitionSource.PROVIDER_WEBHOOK, activatedAt);
            mandate.apply(MandateUpdate.of(MandateStatus.PAUSED), TransitionSource.PROVIDER_WEBHOOK, T0.plus(Duration.ofDays(2)));
            mandate.apply(MandateUpdate.of(MandateStatus.ACTIVE), TransitionSource.PROVIDER_WEBHOOK, T0.plus(Duration.ofDays(3)));

            assertThat(mandate.status()).isEqualTo(MandateStatus.ACTIVE);
            assertThat(mandate.activatedAt()).isEqualTo(activatedAt);
            assertThat(mandate.nextAction()).isNull();
            assertThat(mandate.nextCheckAt()).as("an active mandate is next looked at when it ends").isEqualTo(END);
            assertThat(mandate.providerReference()).isEqualTo("reg_1");
            assertThat(mandate.providerMandateReference()).isEqualTo("tok_1");
            assertThat(mandate.providerCustomerReference()).isEqualTo("cust_psp_1");
            assertThat(mandate.pullEvents()).extracting(PaymentEvent::type).containsExactly(
                    PaymentEvent.Type.MANDATE_ACTIVATED, PaymentEvent.Type.MANDATE_PAUSED, PaymentEvent.Type.MANDATE_RESUMED);
            assertThat(mandate.pullChanges()).extracting(StatusChange::toStatus).containsExactly(
                    "CREATED", "PENDING_AUTHORIZATION", "ACTIVE", "PAUSED", "ACTIVE");
        }

        @Test
        void staleEvidenceIsIgnoredButReferencesAreKept() {
            Mandate mandate = active(MandateInstrument.UPI_AUTOPAY);

            assertThat(mandate.apply(new MandateUpdate(MandateStatus.PENDING_AUTHORIZATION, "reg_other", "tok_other", null,
                    NextAction.redirect("https://psp.example/x"), null), TransitionSource.STATUS_CHECK, T0)).isFalse();
            assertThat(mandate.nextAction()).as("an active mandate has nothing for the customer to do").isNull();
            mandate.revoke(TransitionSource.API, "requested_by_merchant", T0);
            assertThat(mandate.apply(MandateUpdate.of(MandateStatus.ACTIVE), TransitionSource.PROVIDER_WEBHOOK, T0)).isFalse();

            assertThat(mandate.status()).isEqualTo(MandateStatus.REVOKED);
            assertThat(mandate.providerReference()).as("filled once, never replaced").isEqualTo("reg_1");
            assertThat(mandate.providerMandateReference()).isEqualTo("tok_1");
        }

        @Test
        void referencesArriveWithoutAStatusChange() {
            Mandate mandate = newMandate(MandateInstrument.ENACH);

            assertThat(mandate.apply(new MandateUpdate(null, "reg_1", null, null, null, null), TransitionSource.STATUS_CHECK, T0))
                    .isFalse();

            assertThat(mandate.providerReference()).isEqualTo("reg_1");
            assertThat(mandate.isDirty()).isTrue();
            assertThat(mandate.status()).isEqualTo(MandateStatus.CREATED);
        }

        @Test
        void failureKeepsTheCodeAndEndsChecks() {
            Mandate withReason = newMandate(MandateInstrument.CARD);
            withReason.apply(MandateUpdate.failed(new Failure("mandate_rejected", FailureCategory.CUSTOMER, "Rejected")),
                    TransitionSource.PROVIDER_WEBHOOK, T0);
            Mandate withoutReason = newMandate(MandateInstrument.CARD);
            withoutReason.apply(MandateUpdate.of(MandateStatus.FAILED), TransitionSource.PROVIDER_WEBHOOK, T0);

            assertThat(withReason.failureCode()).isEqualTo("mandate_rejected");
            assertThat(withReason.failureMessage()).isEqualTo("Rejected");
            assertThat(withReason.nextCheckAt()).isNull();
            assertThat(withoutReason.failureCode()).isEqualTo("mandate_failed");
            assertThat(withReason.pullEvents()).extracting(PaymentEvent::type).containsExactly(PaymentEvent.Type.MANDATE_FAILED);
        }

        @Test
        void revocationHappensOnce() {
            Mandate mandate = active(MandateInstrument.UPI_AUTOPAY);

            assertThat(mandate.revoke(TransitionSource.API, "requested_by_merchant", T0)).isTrue();
            assertThat(mandate.revoke(TransitionSource.API, "requested_by_merchant", T0)).isFalse();

            assertThat(mandate.pullEvents()).extracting(PaymentEvent::type).containsExactly(PaymentEvent.Type.MANDATE_REVOKED);
            assertThat(mandate.pullChanges()).extracting(StatusChange::reason).containsExactly("requested_by_merchant");
        }

        @Test
        void activeAndPausedMandatesExpireAtTheEndOfTheirTerm() {
            Mandate mandate = active(MandateInstrument.ENACH);
            Mandate paused = active(MandateInstrument.ENACH);
            paused.apply(MandateUpdate.of(MandateStatus.PAUSED), TransitionSource.PROVIDER_WEBHOOK, T0);
            Mandate pending = newMandate(MandateInstrument.ENACH);

            assertThat(mandate.expireIfDue(END.minusNanos(1_000))).isFalse();
            assertThat(mandate.expireIfDue(END)).isTrue();
            assertThat(paused.expireIfDue(END)).isTrue();
            assertThat(pending.expireIfDue(END)).isFalse();
            assertThat(mandate.status()).isEqualTo(MandateStatus.EXPIRED);
            assertThat(mandate.pullEvents()).extracting(PaymentEvent::type).containsExactly(PaymentEvent.Type.MANDATE_EXPIRED);
        }

        @Test
        void authorizationLapsesAtItsDeadlineOnlyBeforeActivation() {
            Mandate pending = newMandate(MandateInstrument.UPI_AUTOPAY);
            Mandate activated = active(MandateInstrument.UPI_AUTOPAY);

            assertThat(pending.authorizationLapsed(AUTHORIZE_BY.minusNanos(1_000))).isFalse();
            assertThat(pending.authorizationLapsed(AUTHORIZE_BY)).isTrue();
            assertThat(activated.authorizationLapsed(AUTHORIZE_BY)).isFalse();
        }

        @Test
        void aCustomerWhoAuthorizedWaitsForThePspPastTheWindow() {
            Mandate mandate = newMandate(MandateInstrument.ENACH);
            mandate.apply(new MandateUpdate(MandateStatus.PENDING_AUTHORIZATION, "reg_1", null, null,
                    NextAction.redirect("https://psp.example/approve"), null), TransitionSource.PROVIDER_RESPONSE, T0);
            assertThat(mandate.authorizationLapsed(AUTHORIZE_BY)).as("the customer has not acted").isTrue();

            mandate.apply(MandateUpdate.of(MandateStatus.PENDING_AUTHORIZATION), TransitionSource.PROVIDER_WEBHOOK, T0);

            assertThat(mandate.nextAction()).isNull();
            assertThat(mandate.status()).isEqualTo(MandateStatus.PENDING_AUTHORIZATION);
            assertThat(mandate.authorizationLapsed(AUTHORIZE_BY.plus(Duration.ofDays(2)))).isFalse();
            Mandate unknown = newMandate(MandateInstrument.ENACH);
            unknown.apply(new MandateUpdate(null, "reg_1", null, null, null, null), TransitionSource.STATUS_CHECK, T0);
            assertThat(unknown.authorizationLapsed(AUTHORIZE_BY)).as("no answer is not an authorization").isTrue();
        }

        @Test
        void pendingChecksBackOffAndTheLastOneIsAtTheDeadline() {
            Mandate mandate = newMandate(MandateInstrument.UPI_AUTOPAY);
            mandate.apply(MandateUpdate.of(MandateStatus.PENDING_AUTHORIZATION), TransitionSource.PROVIDER_RESPONSE, T0);

            mandate.recordCheck(T0);
            assertThat(mandate.nextCheckAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
            mandate.recordCheck(T0);
            assertThat(mandate.nextCheckAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
            mandate.recordCheck(T0);
            assertThat(mandate.nextCheckAt()).isEqualTo(T0.plus(Duration.ofHours(1)));
            mandate.recordCheck(T0);
            assertThat(mandate.nextCheckAt()).as("hourly from then on").isEqualTo(T0.plus(Duration.ofHours(1)));
            Instant late = AUTHORIZE_BY.minus(Duration.ofMinutes(10));
            mandate.recordCheck(late);
            assertThat(mandate.nextCheckAt()).isEqualTo(AUTHORIZE_BY);
            mandate.recordCheck(AUTHORIZE_BY);
            assertThat(mandate.nextCheckAt()).as("past the deadline the hourly check decides").isEqualTo(
                    AUTHORIZE_BY.plus(Duration.ofHours(1)));
        }

        @Test
        void checksOfActiveMandatesWaitForTheEndAndFinalMandatesAreNotChecked() {
            Mandate mandate = active(MandateInstrument.CARD);
            mandate.recordCheck(T0);
            assertThat(mandate.nextCheckAt()).isEqualTo(END);

            mandate.revoke(TransitionSource.API, "requested_by_merchant", T0);
            mandate.recordCheck(T0);
            assertThat(mandate.nextCheckAt()).isNull();
        }
    }

    @Nested
    class DebitRules {

        @Test
        void onlyAnActiveMandateIsDebited() {
            Mandate pending = newMandate(MandateInstrument.UPI_AUTOPAY);
            Mandate paused = active(MandateInstrument.UPI_AUTOPAY);
            paused.apply(MandateUpdate.of(MandateStatus.PAUSED), TransitionSource.PROVIDER_WEBHOOK, T0);

            for (Mandate mandate : new Mandate[] {pending, paused}) {
                assertThatThrownBy(() -> debit(mandate, 10_000, T0.plus(Duration.ofDays(2)), T0))
                        .isInstanceOfSatisfying(GatewayException.class,
                                e -> assertThat(e.code()).isEqualTo(ErrorCode.MANDATE_INVALID_STATE));
            }
        }

        @Test
        void amountsUpToTheLimitsAreAccepted() {
            assertThat(debit(active(MandateInstrument.UPI_AUTOPAY), FRICTIONLESS, T0.plus(Duration.ofDays(2)), T0).amount())
                    .isEqualTo(Money.of(FRICTIONLESS, "INR"));
            assertThat(debit(active(MandateInstrument.ENACH), MAX.amount(), T0.plus(Duration.ofDays(2)), T0).amount())
                    .as("eNACH has no frictionless limit").isEqualTo(MAX);
        }

        @Test
        void amountsAboveTheLimitsAreRefused() {
            assertThatThrownBy(() -> debit(active(MandateInstrument.ENACH), MAX.amount() + 1, T0.plus(Duration.ofDays(2)), T0))
                    .isInstanceOfSatisfying(GatewayException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.AMOUNT_EXCEEDS_MANDATE_LIMIT))
                    .hasMessageContaining("max_amount");
            for (MandateInstrument instrument : new MandateInstrument[] {MandateInstrument.UPI_AUTOPAY, MandateInstrument.CARD}) {
                assertThatThrownBy(() -> debit(active(instrument), FRICTIONLESS + 1, T0.plus(Duration.ofDays(2)), T0))
                        .isInstanceOfSatisfying(GatewayException.class,
                                e -> assertThat(e.code()).isEqualTo(ErrorCode.AMOUNT_EXCEEDS_MANDATE_LIMIT))
                        .hasMessageContaining("additional authentication");
            }
        }

        @Test
        void debitsUseTheMandatesCurrencyAndTerm() {
            Mandate mandate = active(MandateInstrument.CARD);

            assertThatThrownBy(() -> mandate.newDebit("mdd_1", "pay_1", "inv_1", Money.of(10_000, "USD"), FRICTIONLESS,
                    T0.plus(Duration.ofDays(2)), null, NOTIFY_AHEAD, T0)).hasMessageContaining("currency");
            assertThatThrownBy(() -> debit(mandate, 10_000, T0.minusNanos(1_000), T0)).hasMessageContaining("due_at");
            assertThatThrownBy(() -> debit(mandate, 10_000, END, T0)).hasMessageContaining("due_at");
            assertThat(debit(mandate, 10_000, T0, T0).dueAt()).isEqualTo(T0);
        }
    }

    @Nested
    class DebitCycle {

        @Test
        void notifiedInstrumentsAreNotifiedAheadOfTheDueDate() {
            Instant due = T0.plus(Duration.ofDays(3));
            MandateDebit later = debit(active(MandateInstrument.UPI_AUTOPAY), 10_000, due, T0);
            MandateDebit soon = debit(active(MandateInstrument.CARD), 10_000, T0.plus(Duration.ofHours(25)), T0);

            assertThat(later.status()).isEqualTo(MandateDebitStatus.SCHEDULED);
            assertThat(later.requiresNotification()).isTrue();
            assertThat(later.notBefore()).isEqualTo(due);
            assertThat(later.nextActionAt()).isEqualTo(due.minus(NOTIFY_AHEAD));
            assertThat(soon.nextActionAt()).isEqualTo(T0);
            assertThat(later.notificationId()).isEqualTo("mdd_1.1");
        }

        @Test
        void enachDebitsAreReadyAtTheDueDate() {
            Instant due = T0.plus(Duration.ofDays(3));
            MandateDebit debit = debit(active(MandateInstrument.ENACH), 10_000, due, T0);

            assertThat(debit.status()).isEqualTo(MandateDebitStatus.READY);
            assertThat(debit.requiresNotification()).isFalse();
            assertThat(debit.nextActionAt()).isEqualTo(due);
        }

        @Test
        void aDeliveredNotificationAllowsTheDebitAfterTheLeadAndNotBeforeTheDueDate() {
            MandateDebit early = debit(active(MandateInstrument.UPI_AUTOPAY), 10_000, T0.plus(Duration.ofDays(3)), T0);
            early.notificationRequested("ntf_1", CHECK, T0);
            assertThat(early.status()).isEqualTo(MandateDebitStatus.NOTIFYING);
            assertThat(early.nextActionAt()).isEqualTo(T0.plus(CHECK));
            Instant delivered = T0.plus(Duration.ofMinutes(2));
            assertThat(early.notificationDelivered("ntf_1", delivered, LEAD, TransitionSource.STATUS_CHECK, T0)).isTrue();
            MandateDebit late = debit(active(MandateInstrument.UPI_AUTOPAY), 10_000, T0.plus(Duration.ofHours(1)), T0);
            late.notificationDelivered("ntf_2", delivered, LEAD, TransitionSource.PROVIDER_WEBHOOK, T0);

            assertThat(early.status()).isEqualTo(MandateDebitStatus.READY);
            assertThat(early.nextActionAt()).as("the due date is later than the lead").isEqualTo(T0.plus(Duration.ofDays(3)));
            assertThat(late.nextActionAt()).as("the lead ends after the due date").isEqualTo(delivered.plus(LEAD));
            assertThat(late.notificationReference()).as("learned from the delivery").isEqualTo("ntf_2");
            assertThat(late.notifiedAt()).isEqualTo(delivered);
            assertThat(early.notificationDelivered("ntf_1", delivered, LEAD, TransitionSource.PROVIDER_WEBHOOK, T0))
                    .as("a second delivery report changes nothing").isFalse();
        }

        @Test
        void notificationIsOverdueAtTheTimeoutAfterTheEarliestExecution() {
            Instant due = T0.plus(Duration.ofHours(25));
            MandateDebit debit = debit(active(MandateInstrument.UPI_AUTOPAY), 10_000, due, T0);

            assertThat(debit.notificationOverdue(TIMEOUT, due.plus(TIMEOUT).minusNanos(1_000))).isFalse();
            assertThat(debit.notificationOverdue(TIMEOUT, due.plus(TIMEOUT))).isTrue();
            debit.notificationRequested("ntf_1", CHECK, T0);
            assertThat(debit.notificationOverdue(TIMEOUT, due.plus(TIMEOUT))).isTrue();
            debit.notificationDelivered("ntf_1", T0, LEAD, TransitionSource.STATUS_CHECK, T0);
            assertThat(debit.notificationOverdue(TIMEOUT, due.plus(TIMEOUT))).as("delivered").isFalse();
        }

        @Test
        void onlyAReadyDebitStartsExecuting() {
            MandateDebit scheduled = debit(active(MandateInstrument.UPI_AUTOPAY), 10_000, T0.plus(Duration.ofDays(2)), T0);
            MandateDebit ready = debit(active(MandateInstrument.ENACH), 10_000, T0, T0);

            assertThatThrownBy(() -> scheduled.executionStarted(T0)).isInstanceOf(IllegalStateException.class);
            ready.executionStarted(T0);
            assertThat(ready.status()).isEqualTo(MandateDebitStatus.EXECUTING);
            assertThat(ready.lastExecutedAt()).isEqualTo(T0);
            assertThat(ready.nextActionAt()).isNull();
        }

        @Test
        void aFailedAttemptWithRetriesLeftStartsTheNextCycleWithANewNotification() {
            MandateDebit debit = executing(MandateInstrument.UPI_AUTOPAY);
            Instant failedAt = T0.plus(Duration.ofDays(2));

            assertThat(debit.followPayment(PaymentStatus.REQUIRES_PAYMENT_METHOD, "declined", "Declined", RETRY,
                    NOTIFY_AHEAD, failedAt)).isTrue();

            assertThat(debit.status()).isEqualTo(MandateDebitStatus.SCHEDULED);
            assertThat(debit.cycle()).isEqualTo(2);
            assertThat(debit.notificationId()).isEqualTo("mdd_1.2");
            assertThat(debit.notBefore()).isEqualTo(failedAt.plus(RETRY));
            assertThat(debit.nextActionAt()).isEqualTo(failedAt.plus(RETRY).minus(NOTIFY_AHEAD));
            assertThat(debit.notificationReference()).isNull();
            assertThat(debit.notifiedAt()).isNull();
            assertThat(debit.lastExecutedAt()).isNull();
        }

        @Test
        void anEnachRetryIsReadyAfterTheRetryInterval() {
            MandateDebit debit = executing(MandateInstrument.ENACH);
            Instant failedAt = T0.plus(Duration.ofDays(1));

            debit.followPayment(PaymentStatus.REQUIRES_PAYMENT_METHOD, "declined", null, RETRY, NOTIFY_AHEAD, failedAt);

            assertThat(debit.status()).isEqualTo(MandateDebitStatus.READY);
            assertThat(debit.nextActionAt()).isEqualTo(failedAt.plus(RETRY));
        }

        @Test
        void theDebitEndsWithItsPayment() {
            MandateDebit succeeded = executing(MandateInstrument.UPI_AUTOPAY);
            MandateDebit failed = executing(MandateInstrument.UPI_AUTOPAY);
            MandateDebit expired = executing(MandateInstrument.UPI_AUTOPAY);
            MandateDebit cancelled = debit(active(MandateInstrument.CARD), 10_000, T0.plus(Duration.ofDays(2)), T0);

            succeeded.followPayment(PaymentStatus.SUCCEEDED, null, null, RETRY, NOTIFY_AHEAD, T0);
            failed.followPayment(PaymentStatus.FAILED, "insufficient_funds", "No money", RETRY, NOTIFY_AHEAD, T0);
            expired.followPayment(PaymentStatus.EXPIRED, null, null, RETRY, NOTIFY_AHEAD, T0);
            cancelled.followPayment(PaymentStatus.CANCELLED, null, null, RETRY, NOTIFY_AHEAD, T0);

            assertThat(succeeded.status()).isEqualTo(MandateDebitStatus.SUCCEEDED);
            assertThat(failed.status()).isEqualTo(MandateDebitStatus.FAILED);
            assertThat(failed.failureCode()).isEqualTo("insufficient_funds");
            assertThat(failed.failureMessage()).isEqualTo("No money");
            assertThat(expired.failureCode()).isEqualTo("payment_expired");
            assertThat(cancelled.status()).isEqualTo(MandateDebitStatus.CANCELLED);
            assertThat(cancelled.nextActionAt()).isNull();
            assertThat(succeeded.followPayment(PaymentStatus.FAILED, "late", null, RETRY, NOTIFY_AHEAD, T0))
                    .as("final debits never change").isFalse();
            assertThat(succeeded.status()).isEqualTo(MandateDebitStatus.SUCCEEDED);
        }

        @Test
        void aPaymentAwaitingAMethodMovesOnlyAnExecutingDebit() {
            MandateDebit scheduled = debit(active(MandateInstrument.UPI_AUTOPAY), 10_000, T0.plus(Duration.ofDays(2)), T0);
            MandateDebit executing = executing(MandateInstrument.UPI_AUTOPAY);

            assertThat(scheduled.followPayment(PaymentStatus.REQUIRES_PAYMENT_METHOD, null, null, RETRY, NOTIFY_AHEAD, T0))
                    .isFalse();
            assertThat(executing.followPayment(PaymentStatus.PROCESSING, null, null, RETRY, NOTIFY_AHEAD, T0)).isFalse();
            assertThat(scheduled.cycle()).isEqualTo(1);
            assertThat(executing.status()).isEqualTo(MandateDebitStatus.EXECUTING);
        }

        @Test
        void onlyDebitsThatHaveNotStartedExecutingCanBeCancelled() {
            MandateDebit scheduled = debit(active(MandateInstrument.UPI_AUTOPAY), 10_000, T0.plus(Duration.ofDays(2)), T0);
            MandateDebit notifying = debit(active(MandateInstrument.UPI_AUTOPAY), 10_000, T0.plus(Duration.ofDays(2)), T0);
            notifying.notificationRequested("ntf_1", CHECK, T0);
            MandateDebit ready = debit(active(MandateInstrument.ENACH), 10_000, T0, T0);
            MandateDebit executing = executing(MandateInstrument.ENACH);

            assertThat(scheduled.cancel(TransitionSource.API, "requested_by_merchant", T0)).isTrue();
            assertThat(notifying.cancel(TransitionSource.API, "requested_by_merchant", T0)).isTrue();
            assertThat(ready.cancel(TransitionSource.SYSTEM, "mandate_revoked", T0)).isTrue();
            assertThat(executing.cancel(TransitionSource.API, "requested_by_merchant", T0)).isFalse();
            assertThat(scheduled.nextActionAt()).isNull();
            assertThat(ready.pullChanges()).extracting(StatusChange::reason).containsExactly("created", "mandate_revoked");
        }

        private MandateDebit executing(MandateInstrument instrument) {
            MandateDebit debit = debit(active(instrument), 10_000, T0, T0);
            if (debit.status() == MandateDebitStatus.SCHEDULED) {
                debit.notificationRequested("ntf_1", CHECK, T0);
                debit.notificationDelivered("ntf_1", T0, LEAD, TransitionSource.STATUS_CHECK, T0);
            }
            debit.executionStarted(T0.plus(LEAD));
            return debit;
        }
    }
}
