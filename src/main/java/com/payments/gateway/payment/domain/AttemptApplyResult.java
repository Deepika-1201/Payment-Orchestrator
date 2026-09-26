package com.payments.gateway.payment.domain;

/** What happened when PSP evidence was applied, and which follow-ups the application layer must run. */
public record AttemptApplyResult(Kind kind, String attemptId, RefundInitiator refundInitiator, boolean captureRequired,
                                 boolean voidRequired, boolean lateSuccessAccepted) {

    public enum Kind {
        APPLIED,
        NO_OP,
        CONFLICT,
        AMOUNT_MISMATCH
    }

    public static AttemptApplyResult applied(String attemptId) {
        return new AttemptApplyResult(Kind.APPLIED, attemptId, null, false, false, false);
    }

    public static AttemptApplyResult noOp(String attemptId) {
        return new AttemptApplyResult(Kind.NO_OP, attemptId, null, false, false, false);
    }

    public static AttemptApplyResult conflict(String attemptId) {
        return new AttemptApplyResult(Kind.CONFLICT, attemptId, null, false, false, false);
    }

    public static AttemptApplyResult amountMismatch(String attemptId) {
        return new AttemptApplyResult(Kind.AMOUNT_MISMATCH, attemptId, null, false, false, false);
    }

    public static AttemptApplyResult refund(String attemptId, RefundInitiator initiator) {
        return new AttemptApplyResult(Kind.APPLIED, attemptId, initiator, false, false, false);
    }

    public static AttemptApplyResult capture(String attemptId) {
        return new AttemptApplyResult(Kind.APPLIED, attemptId, null, true, false, false);
    }

    public static AttemptApplyResult voidAuthorization(String attemptId) {
        return new AttemptApplyResult(Kind.APPLIED, attemptId, null, false, true, false);
    }

    public static AttemptApplyResult lateSuccess(String attemptId) {
        return new AttemptApplyResult(Kind.APPLIED, attemptId, null, false, false, true);
    }

    public boolean refundRequired() {
        return refundInitiator != null;
    }
}
