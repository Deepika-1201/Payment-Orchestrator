package com.payments.gateway.provider.mock;

import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory backend of a simulated PSP: its own view of transactions and refunds per merchant account, plus
 * injectable report anomalies used to exercise reconciliation.
 */
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
        private final String merchantId;
        private final String merchantReference;
        private final Money amount;
        private final PaymentMethod method;
        private final boolean manualCapture;
        private final String returnUrl;
        private TxnState state;
        private String failureCode;
        private Instant capturedAt;
        private long refunded;

        Txn(String reference, String merchantId, String merchantReference, Money amount, PaymentMethod method,
            boolean manualCapture, String returnUrl, TxnState state, Instant now) {
            this.reference = reference;
            this.merchantId = merchantId;
            this.merchantReference = merchantReference;
            this.amount = amount;
            this.method = method;
            this.manualCapture = manualCapture;
            this.returnUrl = returnUrl;
            this.state = state;
            this.capturedAt = state == TxnState.CAPTURED ? now : null;
        }

        public String reference() {
            return reference;
        }

        public String merchantId() {
            return merchantId;
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

        public synchronized Instant capturedAt() {
            return capturedAt;
        }

        synchronized void moveTo(TxnState newState, String newFailureCode, Instant now) {
            this.state = newState;
            this.failureCode = newFailureCode;
            if (newState == TxnState.CAPTURED && capturedAt == null) {
                capturedAt = now;
            }
        }

        /** Completes a transaction awaiting the customer; returns false if it is no longer awaiting. */
        synchronized boolean complete(boolean success, Instant now) {
            if (state != TxnState.REQUIRES_ACTION && state != TxnState.PENDING) {
                return false;
            }
            if (success) {
                moveTo(manualCapture ? TxnState.AUTHORIZED : TxnState.CAPTURED, null, now);
            } else {
                moveTo(TxnState.FAILED, "customer_declined", now);
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
                            RefundState state, Instant createdAt) {
    }

    private final String code;
    private final ConcurrentMap<String, Txn> transactions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> transactionsByMerchantRef = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RefundTxn> refunds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> refundsByMerchantRef = new ConcurrentHashMap<>();
    private final List<Txn> orphanCaptures = new CopyOnWriteArrayList<>();
    private final Set<String> droppedFromReport = ConcurrentHashMap.newKeySet();
    private final Set<String> duplicatedInReport = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> reportedAmountOverrides = new ConcurrentHashMap<>();
    private final Map<String, Long> settlementShortfalls = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    MockPsp(String code) {
        this.code = code;
    }

    Txn create(String reference, String merchantId, String merchantReference, Money amount, PaymentMethod method,
               boolean manualCapture, String returnUrl, TxnState state, Instant now) {
        Txn txn = new Txn(reference, merchantId, merchantReference, amount, method, manualCapture, returnUrl, state, now);
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

    List<Txn> capturedBetween(String merchantId, Instant from, Instant to) {
        List<Txn> result = new ArrayList<>();
        for (Txn txn : transactions.values()) {
            if (inWindow(txn, merchantId, from, to)) {
                result.add(txn);
            }
        }
        for (Txn txn : orphanCaptures) {
            if (inWindow(txn, merchantId, from, to)) {
                result.add(txn);
            }
        }
        result.sort((a, b) -> a.capturedAt().compareTo(b.capturedAt()));
        return result;
    }

    List<RefundTxn> refundsBetween(String merchantId, Instant from, Instant to) {
        return refunds.values().stream()
                .filter(refund -> refund.state() == RefundState.SUCCEEDED)
                .filter(refund -> !refund.createdAt().isBefore(from) && refund.createdAt().isBefore(to))
                .filter(refund -> find(refund.paymentReference(), null).map(txn -> txn.merchantId().equals(merchantId)).orElse(false))
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .toList();
    }

    private static boolean inWindow(Txn txn, String merchantId, Instant from, Instant to) {
        Instant capturedAt = txn.capturedAt();
        return txn.merchantId().equals(merchantId) && capturedAt != null
                && !capturedAt.isBefore(from) && capturedAt.isBefore(to);
    }

    // ------------------------------------------------------------------ report anomalies (simulation only)

    public Txn addOrphanCapture(String merchantId, Money amount, Instant now) {
        Txn orphan = new Txn(Ids.newId(code.toLowerCase(Locale.ROOT)), merchantId, null, amount, null, false, null,
                TxnState.CAPTURED, now);
        orphanCaptures.add(orphan);
        return orphan;
    }

    public void dropFromReport(String providerReference) {
        droppedFromReport.add(providerReference);
    }

    public void duplicateInReport(String providerReference) {
        duplicatedInReport.add(providerReference);
    }

    public void overrideReportedAmount(String providerReference, long amount) {
        reportedAmountOverrides.put(providerReference, amount);
    }

    public void shortSettlement(String merchantId, long amount) {
        settlementShortfalls.merge(merchantId, amount, Long::sum);
    }

    public void clearAnomalies() {
        orphanCaptures.clear();
        droppedFromReport.clear();
        duplicatedInReport.clear();
        reportedAmountOverrides.clear();
        settlementShortfalls.clear();
    }

    boolean isDroppedFromReport(String providerReference) {
        return droppedFromReport.contains(providerReference);
    }

    boolean isDuplicatedInReport(String providerReference) {
        return duplicatedInReport.contains(providerReference);
    }

    long reportedAmount(Txn txn) {
        return reportedAmountOverrides.getOrDefault(txn.reference(), txn.amount().amount());
    }

    long settlementShortfall(String merchantId) {
        return settlementShortfalls.getOrDefault(merchantId, 0L);
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }
}
