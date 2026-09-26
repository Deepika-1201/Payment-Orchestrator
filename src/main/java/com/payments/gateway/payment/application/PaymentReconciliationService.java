package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.payment.domain.RefundStatus;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository.AttemptLocator;
import com.payments.gateway.payment.infrastructure.ReconciliationQueries;
import com.payments.gateway.payment.infrastructure.RefundRepository;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The payment module's port for reconciliation: look up internal records for PSP report lines and apply
 * PSP-confirmed outcomes with source RECONCILIATION (auto-heal).
 */
@Service
public class PaymentReconciliationService {

    public enum Kind {
        PAYMENT,
        REFUND
    }

    public record InternalItem(Kind kind, String entityId, String paymentId, String status, Money amount) {

        public boolean succeeded() {
            return "SUCCEEDED".equals(status);
        }
    }

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final ReconciliationQueries queries;
    private final PaymentOutcomeService outcomes;
    private final RefundService refundService;

    public PaymentReconciliationService(PaymentRepository payments, RefundRepository refunds, ReconciliationQueries queries,
                                        PaymentOutcomeService outcomes, RefundService refundService) {
        this.payments = payments;
        this.refunds = refunds;
        this.queries = queries;
        this.outcomes = outcomes;
        this.refundService = refundService;
    }

    /** Scoped to the merchant whose PSP account produced the report. */
    public Optional<InternalItem> findPayment(String merchantId, String providerCode, String providerReference,
                                              String merchantReference) {
        Optional<AttemptLocator> locator = providerReference == null ? Optional.empty()
                : payments.findAttemptByProviderReference(providerCode, providerReference);
        if (locator.isEmpty() && merchantReference != null) {
            locator = payments.findAttemptById(merchantReference).filter(l -> l.providerCode().equals(providerCode));
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
                new AttemptUpdate(AttemptStatus.SUCCEEDED, providerReference, null, null, settledAmount),
                TransitionSource.RECONCILIATION);
        Payment payment = payments.findById(item.paymentId()).orElseThrow();
        return toItem(payment, payment.attempt(item.entityId()).orElseThrow());
    }

    public InternalItem healRefund(InternalItem item, String providerReference, Money settledAmount) {
        return toItem(refundService.applyResult(item.entityId(),
                ProviderRefundResult.succeeded(providerReference, settledAmount), TransitionSource.RECONCILIATION));
    }

    public List<InternalItem> succeededBetween(String merchantId, String providerCode, Instant from, Instant to) {
        return queries.succeededBetween(merchantId, providerCode, from, to).stream()
                .map(s -> new InternalItem("REFUND".equals(s.entity()) ? Kind.REFUND : Kind.PAYMENT, s.entityId(),
                        s.paymentId(), "SUCCEEDED", s.amount()))
                .toList();
    }

    private static InternalItem toItem(Payment payment, PaymentAttempt attempt) {
        return new InternalItem(Kind.PAYMENT, attempt.id(), payment.id(), attempt.status().name(), attempt.amount());
    }

    private static InternalItem toItem(Refund refund) {
        return new InternalItem(Kind.REFUND, refund.id(), refund.paymentId(),
                refund.status() == RefundStatus.SUCCEEDED ? "SUCCEEDED" : refund.status().name(), refund.amount());
    }
}
