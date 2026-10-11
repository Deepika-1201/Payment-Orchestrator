package com.payments.gateway.payout;

import com.payments.gateway.payout.spi.PayoutProvider.Outcome;

public final class PayoutState {
    private PayoutState() {
    }

    public enum Status {
        QUEUED, PENDING, REVIEW, SUCCEEDED, RETURNED, REJECTED, CANCELLED
    }

    public enum Effect {
        NONE, PAID, RELEASE, RETURN, PAID_AND_RETURN
    }

    public record Transition(Status status, Effect effect, boolean conflict) {
    }

    public static Transition apply(Status current, boolean dispatched, Outcome incoming) {
        if (!dispatched) {
            return new Transition(current, Effect.NONE, true);
        }
        if (current == Status.RETURNED) {
            return new Transition(current, Effect.NONE, false);
        }
        if (current == Status.REJECTED || current == Status.CANCELLED) {
            return new Transition(current, Effect.NONE, incoming == Outcome.SUCCEEDED || incoming == Outcome.RETURNED);
        }
        if (current == Status.SUCCEEDED) {
            return incoming == Outcome.RETURNED ? new Transition(Status.RETURNED, Effect.RETURN, false)
                    : new Transition(current, Effect.NONE, incoming == Outcome.REJECTED || incoming == Outcome.FAILURE_UNCONFIRMED);
        }
        return switch (incoming) {
            case SUCCEEDED -> new Transition(Status.SUCCEEDED, Effect.PAID, false);
            case RETURNED -> new Transition(Status.RETURNED, Effect.PAID_AND_RETURN, false);
            case REJECTED -> new Transition(Status.REJECTED, Effect.RELEASE, false);
            case FAILURE_UNCONFIRMED -> new Transition(Status.REVIEW, Effect.NONE, false);
            case PENDING -> new Transition(current == Status.REVIEW ? Status.REVIEW : Status.PENDING, Effect.NONE, false);
            case UNKNOWN, NOT_FOUND -> new Transition(current, Effect.NONE, false);
        };
    }

    public static Transition cancel(Status current, boolean dispatched) {
        if (current == Status.CANCELLED) {
            return new Transition(current, Effect.NONE, false);
        }
        if (dispatched || current != Status.QUEUED) {
            throw new IllegalStateException("Only an undispatched queued payout can be cancelled");
        }
        return new Transition(Status.CANCELLED, Effect.RELEASE, false);
    }
}