package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.Money;
import java.time.Instant;

/**
 * A bank transfer that arrived in a virtual account (ADR-038): recorded once, it is applied to its attempt's payment
 * and/or sent back once. Changed only under its payment's lock; an unmatched credit (no attempt) waits for review.
 */
public final class TransferCredit {

    private final String id;
    private final String merchantId;
    private final String providerCode;
    private final String providerReference;
    private final String collectionReference;
    private final String attemptId;
    private final String paymentId;
    private final Money amount;
    private final String mode;
    private final String utr;
    private final Instant receivedAt;
    private final Instant createdAt;
    private long appliedAmount;
    private long returnedAmount;
    private String returnRefundId;
    private Review review;
    private long version;
    private Instant updatedAt;
    private boolean isNew;

    private TransferCredit(TransferCreditSnapshot s, boolean isNew) {
        this.id = s.id();
        this.merchantId = s.merchantId();
        this.providerCode = s.providerCode();
        this.providerReference = s.providerReference();
        this.collectionReference = s.collectionReference();
        this.attemptId = s.attemptId();
        this.paymentId = s.paymentId();
        this.amount = s.amount();
        this.mode = s.mode();
        this.utr = s.utr();
        this.receivedAt = s.receivedAt();
        this.createdAt = s.createdAt();
        this.appliedAmount = s.appliedAmount();
        this.returnedAmount = s.returnedAmount();
        this.returnRefundId = s.returnRefundId();
        this.review = s.review();
        this.version = s.version();
        this.updatedAt = s.updatedAt();
        this.isNew = isNew;
    }

    /** A credit placed on an attempt; its allocation follows. */
    public static TransferCredit received(String id, String merchantId, String providerCode, String providerReference,
                                          String collectionReference, String attemptId, String paymentId, Money amount,
                                          String mode, String utr, Instant receivedAt, Instant now) {
        return new TransferCredit(new TransferCreditSnapshot(id, merchantId, providerCode, providerReference,
                collectionReference, attemptId, paymentId, amount, mode, utr, receivedAt, 0, 0, null, Review.NONE, 0, now,
                now), true);
    }

    /** A credit to an account no attempt can be found for: kept for review, never sent back automatically. */
    public static TransferCredit unmatched(String id, String merchantId, String providerCode, String providerReference,
                                           String collectionReference, Money amount, String mode, String utr,
                                           Instant receivedAt, Instant now) {
        return new TransferCredit(new TransferCreditSnapshot(id, merchantId, providerCode, providerReference,
                collectionReference, null, null, amount, mode, utr, receivedAt, 0, 0, null,
                Review.NONE.flag(Review.UNMATCHED_CREDIT, now), 0, now, now), true);
    }

    public static TransferCredit rehydrate(TransferCreditSnapshot snapshot) {
        return new TransferCredit(snapshot, false);
    }

    public TransferCreditSnapshot snapshot() {
        return new TransferCreditSnapshot(id, merchantId, providerCode, providerReference, collectionReference, attemptId,
                paymentId, amount, mode, utr, receivedAt, appliedAmount, returnedAmount, returnRefundId, review, version,
                createdAt, updatedAt);
    }

    /** Records the part of a new credit that pays the payment. */
    public void apply(long applied, Instant now) {
        appliedAmount = applied;
        updatedAt = now;
    }

    /** The applied part stops counting (the payment expired short); it can then be sent back. */
    public void releaseApplied(Instant now) {
        appliedAmount = 0;
        updatedAt = now;
    }

    /** Sends back everything not applied through refund {@code refundId}; returns the amount. Done once per credit. */
    public long returnUnapplied(String refundId, Instant now) {
        long amountBack = unapplied();
        returnedAmount = amountBack;
        returnRefundId = refundId;
        updatedAt = now;
        return amountBack;
    }

    /** Acknowledges the review (ADR-016); no money moves. */
    public void resolveReview(Instant now) {
        if (!review.open()) {
            throw com.payments.gateway.shared.error.GatewayException.invalidState("Credit is not awaiting review");
        }
        review = review.resolve();
        updatedAt = now;
    }

    public void markPersisted() {
        if (!isNew) {
            version++;
        }
        isNew = false;
    }

    public long unapplied() {
        return amount.amount() - appliedAmount - returnedAmount;
    }

    public String id() {
        return id;
    }

    public String merchantId() {
        return merchantId;
    }

    public String providerCode() {
        return providerCode;
    }

    public String providerReference() {
        return providerReference;
    }

    public String collectionReference() {
        return collectionReference;
    }

    public String attemptId() {
        return attemptId;
    }

    public String paymentId() {
        return paymentId;
    }

    public Money amount() {
        return amount;
    }

    public String mode() {
        return mode;
    }

    public String utr() {
        return utr;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public long appliedAmount() {
        return appliedAmount;
    }

    public long returnedAmount() {
        return returnedAmount;
    }

    public String returnRefundId() {
        return returnRefundId;
    }

    public Review review() {
        return review;
    }

    public long version() {
        return version;
    }

    public boolean isNew() {
        return isNew;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
