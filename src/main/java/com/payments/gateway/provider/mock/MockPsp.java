package com.payments.gateway.provider.mock;

import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.model.Conversion;
import com.payments.gateway.shared.model.EvidenceCategory;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
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

    public final class Txn {
        private final String reference;
        private final String merchantId;
        private final String accountId;
        private final String webhookSecret;
        private final String merchantReference;
        private final Money amount;
        private final PaymentMethod method;
        private final boolean manualCapture;
        private final String returnUrl;
        private TxnState state;
        private String failureCode;
        private Instant capturedAt;
        private Money captured;
        private Conversion conversion;
        private Integer emiTenureMonths;
        private long refunded;
        // Bank transfers (LLD §21.8): an account (collection) and the credits it received.
        private Instant expiresAt;
        private boolean closed;
        private String collectionReference;
        private String transferMode;
        private String utr;

        Txn(String reference, String merchantId, String accountId, String webhookSecret, String merchantReference,
            Money amount, PaymentMethod method, boolean manualCapture, String returnUrl, TxnState state, Instant now) {
            this.reference = reference;
            this.merchantId = merchantId;
            this.accountId = accountId;
            this.webhookSecret = webhookSecret;
            this.merchantReference = merchantReference;
            this.amount = amount;
            this.method = method;
            this.manualCapture = manualCapture;
            this.returnUrl = returnUrl;
            this.state = state;
            this.capturedAt = state == TxnState.CAPTURED ? now : null;
            this.captured = state == TxnState.CAPTURED ? amount : null;
            this.conversion = state == TxnState.CAPTURED ? convert(amount) : null;
        }

        public String reference() {
            return reference;
        }

        public String merchantId() {
            return merchantId;
        }

        /** The merchant's account at this PSP; its webhooks go to that account's endpoint. */
        public String accountId() {
            return accountId;
        }

        /** The account's own webhook secret, or null to sign with the platform-level mock secret. */
        public String webhookSecret() {
            return webhookSecret;
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

        /** What was captured, or null before the capture; the rest of the authorization was released. */
        public synchronized Money capturedAmount() {
            return captured;
        }

        public synchronized Conversion conversion() {
            return conversion;
        }

        synchronized void moveTo(TxnState newState, String newFailureCode, Instant now) {
            this.state = newState;
            this.failureCode = newFailureCode;
            if (newState == TxnState.CAPTURED && capturedAt == null) {
                capturedAt = now;
                captured = captured == null ? amount : captured;
                conversion = convert(captured);
            }
        }

        /** Captures an authorization for at most its amount, releasing the rest; false if it is not authorized. */
        synchronized boolean capture(Money requested, Instant now) {
            if (state != TxnState.AUTHORIZED) {
                return false;
            }
            captured = requested;
            moveTo(TxnState.CAPTURED, null, now);
            return true;
        }

        /** Completes a transaction awaiting the customer; returns false if it is no longer awaiting. */
        synchronized boolean complete(boolean success, Instant now) {
            return complete(success, null, now);
        }

        /** As {@link #complete(boolean, Instant)}, with the EMI tenure the customer picked on the page. */
        synchronized boolean complete(boolean success, Integer tenureMonths, Instant now) {
            if (state != TxnState.REQUIRES_ACTION && state != TxnState.PENDING) {
                return false;
            }
            if (success) {
                emiTenureMonths = tenureMonths;
                moveTo(manualCapture ? TxnState.AUTHORIZED : TxnState.CAPTURED, null, now);
            } else {
                moveTo(TxnState.FAILED, "customer_declined", now);
            }
            return true;
        }

        /** The EMI tenure picked on the page, or null when none was (the PSP then uses its shortest). */
        public synchronized Integer emiTenureMonths() {
            return emiTenureMonths;
        }

        /** For a credit, the account it arrived in; null otherwise. */
        public String collectionReference() {
            return collectionReference;
        }

        public String transferMode() {
            return transferMode;
        }

        public String utr() {
            return utr;
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        public synchronized boolean closed() {
            return closed;
        }

        /** The account number of a collection, derived from its reference. */
        public String accountNumber() {
            return "2223" + String.format("%010d", Math.floorMod(reference.hashCode(), 10_000_000_000L));
        }

        synchronized boolean reserveRefund(long refundAmount) {
            if (state != TxnState.CAPTURED || refunded + refundAmount > captured.amount()) {
                return false;
            }
            refunded += refundAmount;
            return true;
        }
    }

    public record RefundTxn(String reference, String merchantReference, String paymentReference, Money amount,
                            RefundState state, Instant createdAt, Conversion conversion) {
    }

    public enum MandateState {
        PENDING,
        CONFIRMING,
        ACTIVE,
        PAUSED,
        REVOKED,
        REJECTED
    }

    /** A mandate registration; once the customer authorizes it, the mandate itself (token or UMRN). */
    public static final class MandateTxn {
        private final String reference;
        private final String merchantId;
        private final String accountId;
        private final String webhookSecret;
        private final String merchantReference;
        private final MandateInstrument instrument;
        private final Money maxAmount;
        private final String customerReference;
        private final Txn registration;
        private final String returnUrl;
        private MandateState state = MandateState.PENDING;
        private String mandateReference;

        MandateTxn(String reference, String merchantId, String accountId, String webhookSecret, String merchantReference,
                   MandateInstrument instrument, Money maxAmount, String customerReference, Txn registration,
                   String returnUrl) {
            this.reference = reference;
            this.merchantId = merchantId;
            this.accountId = accountId;
            this.webhookSecret = webhookSecret;
            this.merchantReference = merchantReference;
            this.instrument = instrument;
            this.maxAmount = maxAmount;
            this.customerReference = customerReference;
            this.registration = registration;
            this.returnUrl = returnUrl;
        }

        public String reference() {
            return reference;
        }

        public String merchantId() {
            return merchantId;
        }

        public String accountId() {
            return accountId;
        }

        public String webhookSecret() {
            return webhookSecret;
        }

        /** Our mandate id. */
        public String merchantReference() {
            return merchantReference;
        }

        public MandateInstrument instrument() {
            return instrument;
        }

        public Money maxAmount() {
            return maxAmount;
        }

        public String customerReference() {
            return customerReference;
        }

        /** The authorization charge, or null for instruments registered without one (eNACH). */
        public Txn registration() {
            return registration;
        }

        public String returnUrl() {
            return returnUrl;
        }

        public synchronized MandateState state() {
            return state;
        }

        public synchronized String mandateReference() {
            return mandateReference;
        }

        /** The customer approves or rejects the registration; returns false if it is no longer pending. */
        synchronized boolean authorize(boolean approve, String newMandateReference) {
            if (state != MandateState.PENDING) {
                return false;
            }
            state = approve ? MandateState.ACTIVE : MandateState.REJECTED;
            mandateReference = approve ? newMandateReference : null;
            return true;
        }

        /** The customer approved; the bank has yet to confirm. */
        synchronized boolean awaitConfirmation() {
            if (state != MandateState.PENDING) {
                return false;
            }
            state = MandateState.CONFIRMING;
            return true;
        }

        /** The bank confirms a registration the customer approved. */
        synchronized boolean confirm(String newMandateReference) {
            if (state != MandateState.CONFIRMING) {
                return false;
            }
            state = MandateState.ACTIVE;
            mandateReference = newMandateReference;
            return true;
        }

        /** Pause, resume or revoke, as the customer's UPI or bank app would; returns false if not allowed. */
        synchronized boolean moveTo(MandateState target) {
            boolean allowed = switch (target) {
                case PAUSED -> state == MandateState.ACTIVE;
                case ACTIVE -> state == MandateState.PAUSED;
                case REVOKED -> state == MandateState.PENDING || state == MandateState.CONFIRMING
                        || state == MandateState.ACTIVE || state == MandateState.PAUSED;
                default -> false;
            };
            if (allowed) {
                state = target;
            }
            return allowed;
        }
    }

    /** A pre-debit notification; {@code delivered} is false when the scenario makes it fail. */
    public record Notification(String reference, String notificationId, String mandateReference, String accountId,
                               String webhookSecret, Money amount, boolean delivered, Instant requestedAt) {
    }

    public enum DisputeState {
        OPEN,
        UNDER_REVIEW,
        WON,
        LOST
    }

    /** An evidence document the simulated PSP received for a dispute (ADR-039). */
    public record MockDocument(String id, String fileName, String contentType, int size, String sha256) {
    }

    /** A submitted contest: the statement and the document ids per evidence category. */
    public record MockContest(String statement, Map<EvidenceCategory, List<String>> documents, Instant submittedAt) {
    }

    /** A chargeback on a captured transaction; the disputed amount is withheld from the next settlement. */
    public final class DisputeTxn {
        private final String reference;
        private final Txn payment;
        private final Money amount;
        private final String reason;
        private final Instant createdAt;
        private final Instant respondBy;
        private DisputeState state = DisputeState.OPEN;
        private Instant wonAt;
        private final Conversion conversion;
        private Conversion reversalConversion;
        private final List<MockDocument> documents = new ArrayList<>();
        private MockContest contest;
        private Instant acceptedAt;
        private int responseCalls;

        DisputeTxn(String reference, Txn payment, Money amount, String reason, Instant createdAt, Instant respondBy) {
            this.reference = reference;
            this.payment = payment;
            this.amount = amount;
            this.reason = reason;
            this.createdAt = createdAt;
            this.respondBy = respondBy;
            this.conversion = convert(amount);
        }

        public String reference() {
            return reference;
        }

        public Txn payment() {
            return payment;
        }

        public Money amount() {
            return amount;
        }

        public String reason() {
            return reason;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public Instant respondBy() {
            return respondBy;
        }

        public synchronized DisputeState state() {
            return state;
        }

        public synchronized Instant wonAt() {
            return wonAt;
        }

        public Conversion conversion() {
            return conversion;
        }

        public synchronized Conversion reversalConversion() {
            return reversalConversion;
        }

        synchronized void moveTo(DisputeState newState, Instant now) {
            state = newState;
            if (newState == DisputeState.WON && wonAt == null) {
                wonAt = now;
                reversalConversion = convert(amount);
            }
        }

        public synchronized List<MockDocument> documents() {
            return List.copyOf(documents);
        }

        public synchronized MockContest contest() {
            return contest;
        }

        public synchronized Instant acceptedAt() {
            return acceptedAt;
        }

        synchronized MockDocument addDocument(String fileName, String contentType, int size, String sha256) {
            MockDocument document = new MockDocument(reference + "_doc" + (documents.size() + 1), fileName, contentType,
                    size, sha256);
            documents.add(document);
            return document;
        }

        synchronized boolean hasDocument(String documentId) {
            return documents.stream().anyMatch(document -> document.id().equals(documentId));
        }

        synchronized void submitContest(MockContest submitted) {
            contest = submitted;
            state = DisputeState.UNDER_REVIEW;
        }

        synchronized void accept(Instant now) {
            acceptedAt = now;
            state = DisputeState.LOST;
        }

        /** Counts contest and accept calls, for the scenarios that act on the first one only. */
        synchronized int nextResponseCall() {
            return ++responseCalls;
        }
    }

        private static final Map<String, BigDecimal> DEFAULT_FX_RATES = Map.of(
            "USD", new BigDecimal("83.25"), "EUR", new BigDecimal("90.10"), "GBP", new BigDecimal("105.40"),
            "JPY", new BigDecimal("0.5532"), "KWD", new BigDecimal("270.45"), "BHD", new BigDecimal("220.80"));

        private final ConcurrentMap<String, BigDecimal> fxRates = new ConcurrentHashMap<>(DEFAULT_FX_RATES);
        private final ConcurrentMap<String, DisputeTxn> disputes = new ConcurrentHashMap<>();

    private final String code;
    private final ConcurrentMap<String, Txn> transactions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> transactionsByMerchantRef = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RefundTxn> refunds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> refundsByMerchantRef = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MandateTxn> mandates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> mandatesByMerchantRef = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Notification> notifications = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> notificationsById = new ConcurrentHashMap<>();
    private final List<Txn> orphanCaptures = new CopyOnWriteArrayList<>();
    private final Set<String> droppedFromReport = ConcurrentHashMap.newKeySet();
    private final Set<String> duplicatedInReport = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> reportedAmountOverrides = new ConcurrentHashMap<>();
    private final Map<String, Long> settlementShortfalls = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    MockPsp(String code) {
        this.code = code;
    }

    public Map<String, BigDecimal> fxRates() {
        return Map.copyOf(fxRates);
    }

    public void setFxRate(String currency, BigDecimal rate) {
        if (!DEFAULT_FX_RATES.containsKey(currency) || rate.signum() <= 0) {
            throw new IllegalArgumentException("unsupported currency or nonpositive rate");
        }
        fxRates.put(currency, rate);
    }

    public void resetFxRates() {
        fxRates.clear();
        fxRates.putAll(DEFAULT_FX_RATES);
    }

    Conversion convert(Money amount) {
        if (amount.inSettlementCurrency()) {
            return null;
        }
        BigDecimal rate = fxRates.get(amount.currency());
        BigDecimal major = BigDecimal.valueOf(amount.amount(), Currency.getInstance(amount.currency()).getDefaultFractionDigits());
        long paise = major.multiply(rate).movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
        return new Conversion(Money.of(paise, Money.SETTLEMENT_CURRENCY), rate);
    }

    Txn create(String reference, String merchantId, String accountId, String webhookSecret, String merchantReference,
               Money amount, PaymentMethod method, boolean manualCapture, String returnUrl, TxnState state, Instant now) {
        Txn txn = new Txn(reference, merchantId, accountId, webhookSecret, merchantReference, amount, method,
                manualCapture, returnUrl, state, now);
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

    /** Marks a new transaction as a bank transfer account (collection) that closes at {@code expiresAt}. */
    void openCollection(Txn collection, Instant expiresAt) {
        synchronized (collection) {
            collection.expiresAt = expiresAt;
        }
    }

    /** A transfer into a collection: a captured payment of its own, without a merchant reference (LLD §21.8). */
    public Txn credit(Txn collection, Money amount, String mode, Instant now) {
        Txn credit = new Txn(Ids.newId(code.toLowerCase(Locale.ROOT) + "_cr"), collection.merchantId(),
                collection.accountId(), collection.webhookSecret(), null, amount, PaymentMethod.bankTransfer(), false, null,
                TxnState.CAPTURED, now);
        credit.collectionReference = collection.reference();
        credit.transferMode = mode;
        credit.utr = String.format("UTR%012d", Math.floorMod(credit.reference().hashCode(), 1_000_000_000_000L));
        transactions.put(credit.reference(), credit);
        return credit;
    }

    public List<Txn> creditsOf(String collectionReference) {
        return transactions.values().stream()
                .filter(txn -> collectionReference.equals(txn.collectionReference()))
                .sorted(java.util.Comparator.comparing(Txn::capturedAt).thenComparing(Txn::reference))
                .toList();
    }

    void close(Txn collection) {
        synchronized (collection) {
            collection.closed = true;
        }
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

    MandateTxn saveMandate(MandateTxn mandate) {
        String existing = mandatesByMerchantRef.putIfAbsent(mandate.merchantReference(), mandate.reference());
        if (existing != null) {
            return mandates.get(existing);
        }
        mandates.put(mandate.reference(), mandate);
        return mandate;
    }

    public Optional<MandateTxn> findMandate(String reference, String merchantReference) {
        if (reference != null && mandates.containsKey(reference)) {
            return Optional.of(mandates.get(reference));
        }
        if (merchantReference != null) {
            String ref = mandatesByMerchantRef.get(merchantReference);
            if (ref != null) {
                return Optional.ofNullable(mandates.get(ref));
            }
        }
        return Optional.empty();
    }

    Notification saveNotification(Notification notification) {
        String existing = notificationsById.putIfAbsent(notification.notificationId(), notification.reference());
        if (existing != null) {
            return notifications.get(existing);
        }
        notifications.put(notification.reference(), notification);
        return notification;
    }

    public Optional<Notification> findNotification(String reference, String notificationId) {
        if (reference != null && notifications.containsKey(reference)) {
            return Optional.of(notifications.get(reference));
        }
        if (notificationId != null) {
            String ref = notificationsById.get(notificationId);
            if (ref != null) {
                return Optional.ofNullable(notifications.get(ref));
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

    // ------------------------------------------------------------------ disputes (simulation only)

    public DisputeTxn openDispute(Txn payment, Money amount, String reason, Instant now) {
        DisputeTxn dispute = new DisputeTxn(Ids.newId(code.toLowerCase(Locale.ROOT) + "_dsp"), payment, amount, reason,
                now, now.plus(java.time.Duration.ofDays(7)));
        disputes.put(dispute.reference(), dispute);
        return dispute;
    }

    public Optional<DisputeTxn> findDispute(String reference) {
        return Optional.ofNullable(disputes.get(reference));
    }

    public void moveDispute(DisputeTxn dispute, DisputeState state, Instant now) {
        dispute.moveTo(state, now);
    }

    List<DisputeTxn> disputesOpenedBetween(String merchantId, Instant from, Instant to) {
        return disputes.values().stream()
                .filter(d -> d.payment().merchantId().equals(merchantId))
                .filter(d -> !d.createdAt().isBefore(from) && d.createdAt().isBefore(to))
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .toList();
    }

    List<DisputeTxn> disputesWonBetween(String merchantId, Instant from, Instant to) {
        return disputes.values().stream()
                .filter(d -> d.payment().merchantId().equals(merchantId))
                .filter(d -> d.wonAt() != null && !d.wonAt().isBefore(from) && d.wonAt().isBefore(to))
                .sorted((a, b) -> a.wonAt().compareTo(b.wonAt()))
                .toList();
    }

    // ------------------------------------------------------------------ report anomalies (simulation only)

    public Txn addOrphanCapture(String merchantId, Money amount, Instant now) {
        Txn orphan = new Txn(Ids.newId(code.toLowerCase(Locale.ROOT)), merchantId, null, null, null, amount, null, false,
                null, TxnState.CAPTURED, now);
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
        long amount = txn.conversion() == null ? txn.capturedAmount().amount() : txn.conversion().settled().amount();
        return reportedAmountOverrides.getOrDefault(txn.reference(), amount);
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
