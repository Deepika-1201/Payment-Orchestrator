package com.payments.gateway.provider.mock;

import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory backend of a simulated PSP: its own view of transactions and refunds. */
public final class MockPsp {

    public enum TxnState {
        REQUIRES_ACTION,
        PENDING,
        AUTHORIZED,
        CAPTURED,
        FAILED,
        VOIDED
    }

    public enum RefundState {
        PENDING,
        SUCCEEDED,
        FAILED
    }

    public static final class Txn {
        private final String reference;
        private final String merchantReference;
        private final Money amount;
        private final PaymentMethod method;
        private final boolean manualCapture;
        private final String returnUrl;
        private TxnState state;
        private String failureCode;
        private long refunded;

        Txn(String reference, String merchantReference, Money amount, PaymentMethod method, boolean manualCapture,
            String returnUrl, TxnState state) {
            this.reference = reference;
            this.merchantReference = merchantReference;
            this.amount = amount;
            this.method = method;
            this.manualCapture = manualCapture;
            this.returnUrl = returnUrl;
            this.state = state;
        }

        public String reference() {
            return reference;
        }

        public String merchantReference() {
            return merchantReference;
        }

        public Money amount() {
            return amount;
        }

        public PaymentMethod method() {
            return method;
        }

        public boolean manualCapture() {
            return manualCapture;
        }

        public String returnUrl() {
            return returnUrl;
        }

        public synchronized TxnState state() {
            return state;
        }

        public synchronized String failureCode() {
            return failureCode;
        }

        synchronized void moveTo(TxnState newState, String newFailureCode) {
            this.state = newState;
            this.failureCode = newFailureCode;
        }

        /** Completes a transaction awaiting the customer; returns false if it is no longer awaiting. */
        synchronized boolean complete(boolean success) {
            if (state != TxnState.REQUIRES_ACTION && state != TxnState.PENDING) {
                return false;
            }
            if (success) {
                state = manualCapture ? TxnState.AUTHORIZED : TxnState.CAPTURED;
            } else {
                state = TxnState.FAILED;
                failureCode = "customer_declined";
            }
            return true;
        }

        synchronized boolean reserveRefund(long refundAmount) {
            if (state != TxnState.CAPTURED || refunded + refundAmount > amount.amount()) {
                return false;
            }
            refunded += refundAmount;
            return true;
        }
    }

    public record RefundTxn(String reference, String merchantReference, String paymentReference, Money amount,
                            RefundState state) {
    }

    private final ConcurrentMap<String, Txn> transactions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> transactionsByMerchantRef = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RefundTxn> refunds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> refundsByMerchantRef = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    Txn create(String reference, String merchantReference, Money amount, PaymentMethod method, boolean manualCapture,
               String returnUrl, TxnState state) {
        Txn txn = new Txn(reference, merchantReference, amount, method, manualCapture, returnUrl, state);
        String existing = transactionsByMerchantRef.putIfAbsent(merchantReference, reference);
        if (existing != null) {
            return transactions.get(existing);
        }
        transactions.put(reference, txn);
        return txn;
    }

    public Optional<Txn> find(String reference, String merchantReference) {
        if (reference != null && transactions.containsKey(reference)) {
            return Optional.of(transactions.get(reference));
        }
        if (merchantReference != null) {
            String ref = transactionsByMerchantRef.get(merchantReference);
            if (ref != null) {
                return Optional.ofNullable(transactions.get(ref));
            }
        }
        return Optional.empty();
    }

    RefundTxn saveRefund(RefundTxn refund) {
        String existing = refundsByMerchantRef.putIfAbsent(refund.merchantReference(), refund.reference());
        if (existing != null) {
            return refunds.get(existing);
        }
        refunds.put(refund.reference(), refund);
        return refund;
    }

    Optional<RefundTxn> findRefund(String reference, String merchantReference) {
        if (reference != null && refunds.containsKey(reference)) {
            return Optional.of(refunds.get(reference));
        }
        if (merchantReference != null) {
            String ref = refundsByMerchantRef.get(merchantReference);
            if (ref != null) {
                return Optional.ofNullable(refunds.get(ref));
            }
        }
        return Optional.empty();
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }
}
