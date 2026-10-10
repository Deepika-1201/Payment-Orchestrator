package com.payments.gateway.payment.domain;

/** Merchant-visible domain event; the payload is rendered by the application layer. */
public record PaymentEvent(Type type, String resourceId) {

    public enum Type {
        PAYMENT_AUTHORIZED("payment.authorized"),
        PAYMENT_SUCCEEDED("payment.succeeded"),
        PAYMENT_ATTEMPT_FAILED("payment.attempt_failed"),
        PAYMENT_FAILED("payment.failed"),
        PAYMENT_CANCELLED("payment.cancelled"),
        PAYMENT_EXPIRED("payment.expired"),
        REFUND_SUCCEEDED("refund.succeeded"),
        REFUND_FAILED("refund.failed"),
        DISPUTE_CREATED("dispute.created"),
        DISPUTE_UPDATED("dispute.updated"),
        DISPUTE_WON("dispute.won"),
        DISPUTE_LOST("dispute.lost"),
        DISPUTE_EVIDENCE_DUE("dispute.evidence_due"),
        DISPUTE_RESPONSE_FAILED("dispute.response_failed"),
        MANDATE_ACTIVATED("mandate.activated"),
        MANDATE_PAUSED("mandate.paused"),
        MANDATE_RESUMED("mandate.resumed"),
        MANDATE_REVOKED("mandate.revoked"),
        MANDATE_EXPIRED("mandate.expired"),
        MANDATE_FAILED("mandate.failed");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
