package com.payments.gateway.shared.model;

/** What a piece of dispute evidence proves (ADR-039); each PSP adapter maps these to its own names. */
public enum EvidenceCategory {
    SHIPPING_PROOF,
    BILLING_PROOF,
    CANCELLATION_PROOF,
    CUSTOMER_COMMUNICATION,
    PROOF_OF_SERVICE,
    EXPLANATION_LETTER,
    REFUND_CONFIRMATION,
    ACCESS_ACTIVITY_LOG,
    REFUND_CANCELLATION_POLICY,
    TERMS_AND_CONDITIONS,
    OTHER
}
