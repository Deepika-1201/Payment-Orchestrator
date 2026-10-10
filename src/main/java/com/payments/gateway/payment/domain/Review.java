package com.payments.gateway.payment.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Why an attempt or refund needs a human look (FR-W3, FR-RK1). Reasons accumulate while the review is open;
 * resolving only acknowledges it, it never moves money (ADR-016).
 */
public record Review(boolean open, String reasons, Instant flaggedAt) {

    public static final String STATUS_UNRESOLVED = "status_unresolved";
    public static final String PROVIDER_CONFLICT = "provider_conflict";
    public static final String AMOUNT_MISMATCH = "amount_mismatch";
    public static final String RISK_REVIEW = "risk_review";
    /** A dispute larger than what is left of the captured amount (e.g. the customer was already refunded). */
    public static final String EXCEEDS_NET_CAPTURED = "amount_exceeds_net_captured";
    /** A bank transfer credit to an account no attempt can be found for (ADR-038). */
    public static final String UNMATCHED_CREDIT = "unmatched_credit";
    /** The PSP refused to send a credit back: the customer's money is still at the PSP. */
    public static final String CREDIT_RETURN_FAILED = "credit_return_failed";
    /** The merchant's dispute response was refused by the PSP or not delivered before the deadline (ADR-039). */
    public static final String RESPONSE_FAILED = "response_failed";

    public static final Review NONE = new Review(false, null, null);

    public Review flag(String reason, Instant now) {
        if (!open) {
            return new Review(true, reason, now);
        }
        if (reasonList().contains(reason)) {
            return this;
        }
        return new Review(true, reasons == null ? reason : reasons + "," + reason, flaggedAt);
    }

    public Review resolve() {
        return new Review(false, reasons, flaggedAt);
    }

    public List<String> reasonList() {
        return reasons == null ? List.of() : Arrays.asList(reasons.split(","));
    }
}
