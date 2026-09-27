package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.payment.domain.RefundStatus;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository.ClaimedAttempt;
import com.payments.gateway.payment.infrastructure.RefundRepository;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.shared.config.WorkerProperties;
import com.payments.gateway.shared.model.FailureCategory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves attempts and refunds whose outcome is not final by polling the PSP with backoff (FR-W3). Also the safety
 * net for crashes: stale INITIATED records, pending captures and requested voids are picked up here.
 */
@Component
public class StatusResolver {

    private static final Logger log = LoggerFactory.getLogger(StatusResolver.class);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentOutcomeService outcomes;
    private final RefundService refundService;
    private final ProviderClient providerClient;
    private final WorkerProperties workers;
    private final Clock clock;
    private final MeterRegistry meters;

    public StatusResolver(PaymentRepository payments, RefundRepository refunds, PaymentOutcomeService outcomes,
                          RefundService refundService, ProviderClient providerClient, WorkerProperties workers,
                          Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.refunds = refunds;
        this.outcomes = outcomes;
        this.refundService = refundService;
        this.providerClient = providerClient;
        this.workers = workers;
        this.clock = clock;
        this.meters = meters;
        for (ProviderPaymentResult.Outcome outcome : ProviderPaymentResult.Outcome.values()) {
            meters.counter("pg.status.checks", "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
        }
        meters.counter("pg.status.checks", "outcome", "error");
        Gauge.builder("pg.attempts.unknown", payments, PaymentRepository::countUnknownAttempts)
                .description("Attempts whose outcome is not yet known").register(meters);
        Gauge.builder("pg.attempts.unknown.oldest.age", payments, p -> p.oldestUnknownAttemptAgeSeconds(clock.instant()))
                .baseUnit("seconds").description("Age of the oldest attempt whose outcome is not yet known")
                .register(meters);
    }

    public int resolveDueAttempts() {
        Instant now = clock.instant();
        List<ClaimedAttempt> due = payments.claimDueAttempts(now, now.plus(LEASE), workers.batchSize());
        for (ClaimedAttempt claimed : due) {
            try {
                resolveAttempt(claimed.paymentId(), claimed.attemptId());
            } catch (RuntimeException e) {
                log.warn("Status check of attempt {} failed; will retry after lease expiry", claimed.attemptId(), e);
            }
        }
        return due.size();
    }

    public int resolveDueRefunds() {
        Instant now = clock.instant();
        List<String> due = refunds.claimDue(now, now.plus(LEASE), workers.batchSize());
        for (String refundId : due) {
            try {
                resolveRefund(refundId);
            } catch (RuntimeException e) {
                log.warn("Status check of refund {} failed; will retry after lease expiry", refundId, e);
            }
        }
        return due.size();
    }

    private void resolveAttempt(String paymentId, String attemptId) {
        Payment payment = payments.findById(paymentId).orElse(null);
        PaymentAttempt attempt = payment == null ? null : payment.attempt(attemptId).orElse(null);
        if (attempt == null) {
            return;
        }
        if (attempt.status() == AttemptStatus.AUTHORIZED && attempt.voidRequested()) {
            outcomes.performVoid(paymentId, attemptId);
            return;
        }
        if (!attempt.status().awaitsProvider()) {
            outcomes.recordCheck(paymentId, attemptId);
            return;
        }
        ProviderPaymentResult result;
        try {
            result = providerClient.fetchStatus(payment.merchantId(), attempt.providerCode(),
                    new PaymentStatusQuery(attempt.id(), attempt.providerReference()));
        } catch (ProviderTimeoutException | ProviderUnavailableException e) {
            meters.counter("pg.status.checks", "outcome", "error").increment();
            outcomes.recordCheck(paymentId, attemptId);
            return;
        }
        meters.counter("pg.status.checks", "outcome", result.outcome().name().toLowerCase(java.util.Locale.ROOT)).increment();
        if (result.outcome() == ProviderPaymentResult.Outcome.NOT_FOUND) {
            if (attempt.status() == AttemptStatus.INITIATED || attempt.status() == AttemptStatus.UNKNOWN) {
                outcomes.apply(paymentId, attemptId, AttemptUpdate.failed("not_submitted", FailureCategory.NOT_SUBMITTED,
                        "The provider has no record of this attempt"), TransitionSource.STATUS_CHECK);
            } else {
                outcomes.recordCheck(paymentId, attemptId);
            }
            return;
        }
        if (attempt.status() == AttemptStatus.CAPTURE_PENDING) {
            switch (result.outcome()) {
                case AUTHORIZED -> outcomes.performCapture(paymentId, attemptId);
                case SUCCEEDED -> outcomes.apply(paymentId, attemptId, ProviderResults.toUpdate(result), TransitionSource.STATUS_CHECK);
                default -> outcomes.recordCheck(paymentId, attemptId);
            }
            return;
        }
        outcomes.apply(paymentId, attemptId, ProviderResults.toUpdate(result), TransitionSource.STATUS_CHECK);
    }

    private void resolveRefund(String refundId) {
        Refund refund = refunds.findById(refundId).orElse(null);
        if (refund == null) {
            return;
        }
        if (refund.status().isFinal()) {
            refundService.recordCheck(refundId);
            return;
        }
        ProviderRefundResult result;
        try {
            result = providerClient.fetchRefundStatus(refund.merchantId(), refund.providerCode(),
                    new RefundStatusQuery(refund.id(), refund.providerReference()));
        } catch (ProviderTimeoutException | ProviderUnavailableException e) {
            refundService.recordCheck(refundId);
            return;
        }
        if (result.outcome() == ProviderRefundResult.Outcome.NOT_FOUND) {
            if (refund.status() == RefundStatus.INITIATED || refund.status() == RefundStatus.UNKNOWN) {
                refundService.submit(refundId);
            } else {
                refundService.recordCheck(refundId);
            }
            return;
        }
        refundService.applyResult(refundId, result, TransitionSource.STATUS_CHECK);
    }
}
