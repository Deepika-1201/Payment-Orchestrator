package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.Dispute;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.payment.domain.Review;
import com.payments.gateway.payment.infrastructure.DisputeRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.payment.infrastructure.RefundRepository;
import com.payments.gateway.payment.infrastructure.ReviewQueueRepository;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.web.WireEnums;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual review queue (FR-W3, FR-RK1, ADR-016). Attempts and refunds are flagged by the domain when the gateway cannot
 * decide on its own. Resolving is an audited acknowledgement: it never changes money state, which only provider
 * evidence (webhooks, status checks, reconciliation) may do.
 */
@Service
public class ReviewService {

    public enum Kind {
        ATTEMPT,
        REFUND,
        DISPUTE
    }

    public record ReviewItem(String kind, String id, String paymentId, String merchantId, String providerCode,
                             String status, long amount, String currency, List<String> reasons,
                             List<String> riskReasons, Instant flaggedAt, boolean open) {
    }

    private final ReviewQueueRepository queue;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final DisputeRepository disputes;
    private final PaymentStore store;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReviewService(ReviewQueueRepository queue, PaymentRepository payments, RefundRepository refunds,
                         DisputeRepository disputes, PaymentStore store, AuditLogger audit, TransactionTemplate tx,
                         Clock clock, MeterRegistry meters) {
        this.queue = queue;
        this.payments = payments;
        this.refunds = refunds;
        this.disputes = disputes;
        this.store = store;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        Gauge.builder("pg.reviews.open", queue, ReviewQueueRepository::countOpenAttempts).tag("kind", "attempt")
                .description("Attempts awaiting manual review").register(meters);
        Gauge.builder("pg.reviews.open", queue, ReviewQueueRepository::countOpenRefunds).tag("kind", "refund")
                .description("Refunds awaiting manual review").register(meters);
        Gauge.builder("pg.reviews.open", queue, ReviewQueueRepository::countOpenDisputes).tag("kind", "dispute")
                .description("Disputes awaiting manual review").register(meters);
    }

    /** Oldest first. {@code kind} and {@code merchantId} are optional filters. */
    public List<ReviewItem> open(Kind kind, String merchantId, int limit) {
        return queue.open(kind == null ? null : WireEnums.wire(kind), merchantId, limit).stream()
                .map(row -> new ReviewItem(row.kind(), row.id(), row.paymentId(), row.merchantId(), row.providerCode(),
                        row.status().toLowerCase(java.util.Locale.ROOT), row.amount(), row.currency(), split(row.reasons()),
                        row.riskReasons() == null ? null : split(row.riskReasons()), row.flaggedAt(), true))
                .toList();
    }

    private static List<String> split(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split(","));
    }

    public ReviewItem resolveAttempt(String attemptId, String note, String actor) {
        String paymentId = payments.findAttemptById(attemptId)
                .orElseThrow(() -> GatewayException.notFound("Attempt", attemptId))
                .paymentId();
        Instant now = clock.instant();
        return tx.execute(status -> {
            Payment payment = payments.lockById(paymentId).orElseThrow();
            PaymentAttempt attempt = payment.resolveAttemptReview(attemptId, now);
            store.save(payment);
            audited(actor, "attempt", attemptId, attempt.review(), note);
            return item(Kind.ATTEMPT, attempt.id(), paymentId, attempt.merchantId(), attempt.providerCode(),
                    WireEnums.wire(attempt.status()), attempt.amount(), attempt.review(),
                    attempt.risk() == null ? null : attempt.risk().reasons());
        });
    }

    public ReviewItem resolveRefund(String refundId, String note, String actor) {
        String paymentId = refunds.findById(refundId)
                .orElseThrow(() -> GatewayException.notFound("Refund", refundId))
                .paymentId();
        Instant now = clock.instant();
        return tx.execute(status -> {
            Payment payment = payments.lockById(paymentId).orElseThrow();
            Refund refund = refunds.findById(refundId).orElseThrow();
            refund.resolveReview(now);
            store.saveRefund(payment, refund);
            audited(actor, "refund", refundId, refund.review(), note);
            return item(Kind.REFUND, refund.id(), paymentId, refund.merchantId(), refund.providerCode(),
                    WireEnums.wire(refund.status()), refund.amount(), refund.review(), null);
        });
    }

    public ReviewItem resolveDispute(String disputeId, String note, String actor) {
        String paymentId = disputes.findById(disputeId)
                .orElseThrow(() -> GatewayException.notFound("Dispute", disputeId))
                .paymentId();
        Instant now = clock.instant();
        return tx.execute(status -> {
            Payment payment = payments.lockById(paymentId).orElseThrow();
            Dispute dispute = disputes.findById(disputeId).orElseThrow();
            dispute.resolveReview(now);
            store.saveDispute(payment, dispute);
            audited(actor, "dispute", disputeId, dispute.review(), note);
            return item(Kind.DISPUTE, dispute.id(), paymentId, dispute.merchantId(), dispute.providerCode(),
                    WireEnums.wire(dispute.status()), dispute.amount(), dispute.review(), null);
        });
    }

    private static ReviewItem item(Kind kind, String id, String paymentId, String merchantId, String providerCode,
                                   String status, Money amount, Review review, List<String> riskReasons) {
        return new ReviewItem(WireEnums.wire(kind), id, paymentId, merchantId, providerCode, status, amount.amount(),
                amount.currency(), review.reasonList(), riskReasons, review.flaggedAt(), review.open());
    }

    private void audited(String actor, String resourceType, String id, Review review, String note) {
        audit.record("ADMIN", actor, "review.resolved", resourceType, id,
                Map.of("reasons", review.reasonList(), "note", note));
    }
}
