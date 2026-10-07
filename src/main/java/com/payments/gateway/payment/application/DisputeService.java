package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.Dispute;
import com.payments.gateway.payment.domain.DisputeStatus;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.Review;
import com.payments.gateway.payment.domain.TransitionOutcome;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.DisputeRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository.AttemptLocator;
import com.payments.gateway.payment.infrastructure.RefundRepository;
import com.payments.gateway.provider.spi.ProviderDisputeResult;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.error.GatewayException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disputes and chargebacks (FR-D1, ADR-018): recorded from PSP webhooks and settlement reports under the disputed
 * payment's row lock, with shadow-ledger postings and merchant events in the same transaction. The gateway tracks
 * disputes; evidence is submitted on the PSP's side.
 */
@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final DisputeRepository disputes;
    private final PaymentStore store;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public DisputeService(PaymentRepository payments, RefundRepository refunds, DisputeRepository disputes,
                          PaymentStore store, TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.refunds = refunds;
        this.disputes = disputes;
        this.store = store;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
        meters.counter("pg.provider.conflicts", "source", "dispute");
    }

    /** Returns false (event ignored) when the disputed payment is unknown or belongs to another merchant. */
    public boolean applyProviderEvent(String providerCode, String merchantScope, ProviderEvent event) {
        ProviderDisputeResult result = event.dispute();
        Optional<AttemptLocator> attempt = result.paymentReference() == null ? Optional.empty()
                : payments.findAttemptByProviderReference(providerCode, result.paymentReference());
        if (attempt.isEmpty() && event.merchantReference() != null) {
            attempt = payments.findAttemptById(event.merchantReference()).filter(a -> a.providerCode().equals(providerCode));
        }
        if (attempt.isEmpty()) {
            log.warn("Ignored {} dispute event {}: disputed payment is unknown", providerCode, event.eventId());
            return false;
        }
        if (merchantScope != null && !merchantScope.equals(attempt.get().merchantId())) {
            log.warn("Ignored {} dispute event {} from merchant {}'s account: attempt {} belongs to another merchant",
                    providerCode, event.eventId(), merchantScope, attempt.get().attemptId());
            return false;
        }
        record(attempt.get(), result, TransitionSource.PROVIDER_WEBHOOK);
        return true;
    }

    /** Creates or advances the dispute; also the reconciliation path for chargebacks seen only in a report. */
    public Dispute record(AttemptLocator locator, ProviderDisputeResult result, TransitionSource source) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            Payment payment = payments.lockById(locator.paymentId()).orElseThrow();
            DisputeStatus reported = DisputeStatus.valueOf(result.status().name());
            Optional<Dispute> existing = disputes.findByProviderDisputeId(locator.providerCode(), locator.merchantId(),
                    result.providerDisputeId());
            DisputeStatus before = existing.map(Dispute::status).orElse(null);
            Dispute dispute;
            if (existing.isPresent()) {
                dispute = existing.get();
                if (!dispute.attemptId().equals(locator.attemptId()) || !dispute.amount().equals(result.amount())) {
                    dispute.flagForReview(Review.PROVIDER_CONFLICT, now);
                    meters.counter("pg.provider.conflicts", "source", "dispute").increment();
                }
                if (dispute.apply(reported, result.respondBy(), source, now) == TransitionOutcome.CONFLICT) {
                    meters.counter("pg.provider.conflicts", "source", "dispute").increment();
                }
            } else {
                PaymentAttempt attempt = payment.attempt(locator.attemptId()).orElseThrow();
                long netCaptured = attempt.status() != AttemptStatus.SUCCEEDED ? 0
                        : attempt.capturedAmount().amount() - refunds.sumActiveForAttempt(attempt.id())
                                - disputes.sumHoldingFundsForAttempt(attempt.id());
                dispute = Dispute.open(Ids.newId("dsp"), attempt, result.providerDisputeId(), result.amount(),
                        result.reason(), reported, result.respondBy(), source, now);
                if (!result.amount().currency().equals(attempt.amount().currency()) || result.amount().amount() > netCaptured) {
                    dispute.flagForReview(Review.EXCEEDS_NET_CAPTURED, now);
                }
                meters.counter("pg.disputes.opened", "provider", locator.providerCode()).increment();
                log.info("Dispute {} ({}) opened on payment {} for {}", dispute.id(), result.providerDisputeId(),
                        payment.id(), result.amount());
            }
            store.saveDispute(payment, dispute);
            if (dispute.status().isFinal() && dispute.status() != before) {
                meters.counter("pg.disputes.closed", "provider", locator.providerCode(),
                        "outcome", dispute.status().name().toLowerCase(Locale.ROOT)).increment();
            }
            return dispute;
        });
    }

    public Dispute get(String merchantId, String disputeId) {
        return disputes.findForMerchant(merchantId, disputeId)
                .orElseThrow(() -> GatewayException.notFound("Dispute", disputeId));
    }

    public List<Dispute> listForPayment(String merchantId, String paymentId) {
        payments.findForMerchant(merchantId, paymentId).orElseThrow(() -> GatewayException.notFound("Payment", paymentId));
        return disputes.findByPayment(paymentId);
    }
}
