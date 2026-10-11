package com.payments.gateway.payout;

import com.payments.gateway.payout.PayoutState.Effect;
import com.payments.gateway.payout.PayoutState.Status;
import com.payments.gateway.payout.PayoutState.Transition;
import com.payments.gateway.payout.spi.PayoutProvider.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutStateTest {
    @Test
    void confirmedOutcomesHaveExactlyOneFinancialEffect() {
        assertThat(PayoutState.apply(Status.PENDING, true, Outcome.SUCCEEDED))
                .isEqualTo(new Transition(Status.SUCCEEDED, Effect.PAID, false));
        assertThat(PayoutState.apply(Status.PENDING, true, Outcome.REJECTED))
                .isEqualTo(new Transition(Status.REJECTED, Effect.RELEASE, false));
        assertThat(PayoutState.apply(Status.PENDING, true, Outcome.RETURNED))
                .isEqualTo(new Transition(Status.RETURNED, Effect.PAID_AND_RETURN, false));
        assertThat(PayoutState.apply(Status.SUCCEEDED, true, Outcome.RETURNED))
                .isEqualTo(new Transition(Status.RETURNED, Effect.RETURN, false));
    }

    @Test
    void unknownAndUnconfirmedFailuresNeverReleaseMoney() {
        for (Status state : new Status[] {Status.PENDING, Status.REVIEW}) {
            for (Outcome outcome : new Outcome[] {Outcome.UNKNOWN, Outcome.NOT_FOUND, Outcome.PENDING, Outcome.FAILURE_UNCONFIRMED}) {
                assertThat(PayoutState.apply(state, true, outcome).effect()).isEqualTo(Effect.NONE);
            }
        }
        assertThat(PayoutState.apply(Status.PENDING, true, Outcome.FAILURE_UNCONFIRMED).status()).isEqualTo(Status.REVIEW);
        assertThat(PayoutState.apply(Status.REVIEW, true, Outcome.PENDING).status()).isEqualTo(Status.REVIEW);
        assertThat(PayoutState.apply(Status.REVIEW, true, Outcome.SUCCEEDED).status()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void duplicateAndOutOfOrderEventsCannotPayOrReturnTwice() {
        for (Outcome outcome : Outcome.values()) {
            assertThat(PayoutState.apply(Status.RETURNED, true, outcome))
                    .isEqualTo(new Transition(Status.RETURNED, Effect.NONE, false));
        }
        for (Outcome outcome : new Outcome[] {Outcome.PENDING, Outcome.NOT_FOUND, Outcome.UNKNOWN, Outcome.SUCCEEDED}) {
            assertThat(PayoutState.apply(Status.SUCCEEDED, true, outcome))
                    .isEqualTo(new Transition(Status.SUCCEEDED, Effect.NONE, false));
        }
        assertThat(PayoutState.apply(Status.SUCCEEDED, true, Outcome.REJECTED).conflict()).isTrue();
        assertThat(PayoutState.apply(Status.REJECTED, true, Outcome.SUCCEEDED))
                .isEqualTo(new Transition(Status.REJECTED, Effect.NONE, true));
        assertThat(PayoutState.apply(Status.CANCELLED, true, Outcome.RETURNED).effect()).isEqualTo(Effect.NONE);
    }

    @Test
    void cancellationIsIdempotentAndOnlyBeforeDispatch() {
        assertThat(PayoutState.cancel(Status.QUEUED, false)).isEqualTo(new Transition(Status.CANCELLED, Effect.RELEASE, false));
        assertThat(PayoutState.cancel(Status.CANCELLED, false)).isEqualTo(new Transition(Status.CANCELLED, Effect.NONE, false));
        assertThatThrownBy(() -> PayoutState.cancel(Status.QUEUED, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PayoutState.cancel(Status.PENDING, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PayoutState.cancel(Status.SUCCEEDED, true)).isInstanceOf(IllegalStateException.class);
        assertThat(PayoutState.apply(Status.QUEUED, false, Outcome.SUCCEEDED))
                .isEqualTo(new Transition(Status.QUEUED, Effect.NONE, true));
    }
}