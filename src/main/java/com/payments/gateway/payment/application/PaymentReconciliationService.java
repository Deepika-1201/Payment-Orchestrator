package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.Dispute;
import com.payments.gateway.payment.domain.DisputeStatus;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.payment.domain.RefundStatus;
import com.payments.gateway.payment.domain.TransferCredit;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.DisputeRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository.AttemptLocator;
import com.payments.gateway.payment.infrastructure.ReconciliationQueries;
import com.payments.gateway.payment.infrastructure.RefundRepository;
import com.payments.gateway.payment.infrastructure.TransferCreditRepository;
import com.payments.gateway.provider.spi.ProviderDisputeResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.shared.events.CurrencyConversion;
import com.payments.gateway.shared.model.Conversion;
import com.payments.gateway.shared.model.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The payment module's port for reconciliation: look up internal records for PSP report lines and apply
 * PSP-confirmed outcomes with source RECONCILIATION (auto-heal).
 */
@Service
public class PaymentReconciliationService {

    public enum Kind {
        PAYMENT,
        REFUND,
        DISPUTE,
        /** A bank transfer credit: settled as a payment line of its own (LLD §21.6). */
        CREDIT
    }

    public record InternalItem(Kind kind, String entityId, String paymentId, String status, Money amount) {

        public boolean succeeded() {
            return "SUCCEEDED".equals(status);
        }
    }

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final DisputeRepository disputes;
    private final TransferCreditRepository credits;
    private final ReconciliationQueries queries;
    private final PaymentOutcomeService outcomes;
    private final RefundService refundService;
    private final DisputeService disputeService;
    private final PaymentConversionService conversions;
    private final PaymentStore store;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentReconciliationService(PaymentRepository payments, RefundRepository refunds, DisputeRepository disputes,
                                        TransferCreditRepository credits, ReconciliationQueries queries,
                                        PaymentOutcomeService outcomes, RefundService refundService,
                                        DisputeService disputeService, PaymentConversionService conversions,
                                        PaymentStore store, TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.disputes = disputes;
        this.credits = credits;
        this.queries = queries;
        this.outcomes = outcomes;
        this.refundService = refundService;
        this.disputeService = disputeService;
        this.conversions = conversions;
        this.store = store;
        this.tx = tx;
        this.clock = clock;
    }

    /** Scoped to the merchant whose PSP account produced the report. */
    public Optional<InternalItem> findPayment(String merchantId, String providerCode, String providerReference,
                                              String merchantReference) {
        Optional<AttemptLocator> locator = providerReference == null ? Optional.empty()
                : payments.findAttemptByProviderReference(providerCode, providerReference);
        if (locator.isEmpty() && merchantReference != null) {
            locator = payments.findAttemptById(merchantReference).filter(l -> l.providerCode().equals(providerCode));
        }
        if (locator.isEmpty()) {
            return providerReference == null ? Optional.empty()
                    : credits.findByProviderReference(providerCode, providerReference)
                            .filter(credit -> credit.merchantId().equals(merchantId))
                            .map(PaymentReconciliationService::toItem);
        }
        return locator.filter(l -> l.merchantId().equals(merchantId)).flatMap(l -> payments.findById(l.paymentId())
                .flatMap(payment -> payment.attempt(l.attemptId()).map(attempt -> toItem(payment, attempt))));
    }

    public Optional<InternalItem> findRefund(String merchantId, String providerCode, String providerReference,
                                             String merchantReference) {
        Optional<Refund> refund = providerReference == null ? Optional.empty()
                : refunds.findByProviderReference(providerCode, providerReference);
        if (refund.isEmpty() && merchantReference != null) {
            refund = refunds.findById(merchantReference).filter(r -> r.providerCode().equals(providerCode));
        }
        return refund.filter(r -> r.merchantId().equals(merchantId)).map(PaymentReconciliationService::toItem);
    }

    /** Applies a PSP-settled capture; the result reflects the attempt after the domain rules ran. */
    public InternalItem healPayment(InternalItem item, String providerReference, Money settledAmount) {
        outcomes.apply(item.paymentId(), item.entityId(),
            new AttemptUpdate(AttemptStatus.SUCCEEDED, providerReference, null, null,
                item.amount().inSettlementCurrency() ? settledAmount : item.amount(), null,
                item.amount().inSettlementCurrency() ? null : new Conversion(settledAmount, null)),
                TransitionSource.RECONCILIATION);
        Payment payment = payments.findById(item.paymentId()).orElseThrow();
        return toItem(payment, payment.attempt(item.entityId()).orElseThrow());
    }

    public InternalItem healRefund(InternalItem item, String providerReference, Money settledAmount) {
        return toItem(refundService.applyResult(item.entityId(),
                ProviderRefundResult.succeeded(providerReference, item.amount().inSettlementCurrency() ? settledAmount : item.amount())
                        .withConversion(item.amount().inSettlementCurrency() ? null : new Conversion(settledAmount, null)),
                TransitionSource.RECONCILIATION));
    }

    public Optional<Conversion> conversion(InternalItem item, CurrencyConversion.Kind kind) {
        return conversions.find(kind, item.entityId());
    }

    public PaymentConversionService.Result recordConversion(InternalItem item, CurrencyConversion.Kind kind, Money settled) {
        return tx.execute(status -> {
            Payment payment = payments.lockById(item.paymentId()).orElseThrow();
            Refund refund = item.kind() == Kind.REFUND ? refunds.findById(item.entityId()).orElseThrow() : null;
            Dispute dispute = item.kind() == Kind.DISPUTE ? disputes.findById(item.entityId()).orElseThrow() : null;
            if ((refund != null && refund.status() != RefundStatus.SUCCEEDED)
                    || (dispute != null && kind == CurrencyConversion.Kind.CHARGEBACK_REVERSAL && dispute.status() != DisputeStatus.WON)) {
                return PaymentConversionService.Result.MISSING_DEPENDENCY;
            }
            String attemptId = refund != null ? refund.attemptId() : dispute != null ? dispute.attemptId() : item.entityId();
            PaymentConversionService.Result result = conversions.record(payment, attemptId, kind, item.entityId(), item.amount(),
                    new Conversion(settled, null), PaymentConversionService.Source.SETTLEMENT_REPORT);
            if (result == PaymentConversionService.Result.RECORDED) {
                Instant now = clock.instant();
                payment.touch(now);
                if (refund != null) {
                    refund.touch(now);
                }
                store.save(payment, refund, List.of());
                if (dispute != null) {
                    dispute.touch(now);
                    store.saveDispute(payment, dispute);
                }
            }
            return result;
        });
    }

    public List<InternalItem> succeededBetween(String merchantId, String providerCode, Instant from, Instant to) {
        List<InternalItem> items = new ArrayList<>(queries.succeededBetween(merchantId, providerCode, from, to).stream()
                .map(s -> new InternalItem("REFUND".equals(s.entity()) ? Kind.REFUND : Kind.PAYMENT, s.entityId(),
                        s.paymentId(), "SUCCEEDED", s.amount()))
                .toList());
        credits.findReceivedBetween(merchantId, providerCode, from, to).forEach(credit -> items.add(toItem(credit)));
        return items;
    }

    private static InternalItem toItem(TransferCredit credit) {
        return new InternalItem(Kind.CREDIT, credit.id(), credit.paymentId(), "SUCCEEDED", credit.amount());
    }

    private static InternalItem toItem(Payment payment, PaymentAttempt attempt) {
        return new InternalItem(Kind.PAYMENT, attempt.id(), payment.id(), attempt.status().name(), attempt.capturedAmount());
    }

    private static InternalItem toItem(Refund refund) {
        return new InternalItem(Kind.REFUND, refund.id(), refund.paymentId(),
                refund.status() == RefundStatus.SUCCEEDED ? "SUCCEEDED" : refund.status().name(), refund.amount());
    }

    public Optional<InternalItem> findDispute(String merchantId, String providerCode, String providerDisputeId) {
        return disputes.findByProviderDisputeId(providerCode, merchantId, providerDisputeId)
                .map(PaymentReconciliationService::toItem);
    }

    /**
     * A chargeback the PSP withheld but never sent a webhook for: recorded as the webhook would have been (source
     * RECONCILIATION). Empty when the disputed attempt is unknown to this merchant's account.
     */
    public Optional<InternalItem> ingestDispute(String merchantId, String providerCode, String providerDisputeId,
                                                String attemptId, Money amount) {
        Optional<AttemptLocator> locator = attemptId == null ? Optional.empty()
                : payments.findAttemptById(attemptId)
                .filter(l -> l.providerCode().equals(providerCode) && l.merchantId().equals(merchantId))
                .filter(found -> payments.findById(found.paymentId()).orElseThrow().amount().currency().equals(amount.currency()));
        return locator.map(l -> toItem(disputeService.record(l, new ProviderDisputeResult(providerDisputeId, null,
                ProviderDisputeResult.Status.OPEN, amount, "reported_in_settlement", null, "settlement_report"),
                TransitionSource.RECONCILIATION)));
    }

    /** A chargeback reversal line: the PSP returned the funds, so the dispute was won. */
    public InternalItem healDisputeWon(InternalItem item) {
        Dispute dispute = disputes.findById(item.entityId()).orElseThrow();
        AttemptLocator locator = payments.findAttemptById(dispute.attemptId()).orElseThrow();
        return toItem(disputeService.record(locator, new ProviderDisputeResult(dispute.providerDisputeId(), null,
                ProviderDisputeResult.Status.WON, dispute.amount(), null, null, "settlement_report"),
                TransitionSource.RECONCILIATION));
    }

    private static InternalItem toItem(Dispute dispute) {
        return new InternalItem(Kind.DISPUTE, dispute.id(), dispute.paymentId(), dispute.status().name(), dispute.amount());
    }
}
