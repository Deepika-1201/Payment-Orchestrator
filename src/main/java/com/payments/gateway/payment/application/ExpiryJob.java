package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.shared.config.WorkerProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Expires abandoned payments and lapses uncaptured authorizations (FR-P7). */
@Component
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);

    private final PaymentRepository payments;
    private final PaymentStore store;
    private final PaymentOutcomeService outcomes;
    private final PaymentProperties properties;
    private final WorkerProperties workers;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ExpiryJob(PaymentRepository payments, PaymentStore store, PaymentOutcomeService outcomes,
                     PaymentProperties properties, WorkerProperties workers, TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.store = store;
        this.outcomes = outcomes;
        this.properties = properties;
        this.workers = workers;
        this.tx = tx;
        this.clock = clock;
    }

    public int expireDue() {
        Instant now = clock.instant();
        List<String> candidates = payments.findExpirable(now, now.minus(properties.processingGrace()), workers.batchSize());
        int expired = 0;
        for (String paymentId : candidates) {
            try {
                Payment.ExpireResult result = tx.execute(status -> {
                    Payment payment = payments.lockById(paymentId).orElse(null);
                    if (payment == null) {
                        return new Payment.ExpireResult(false, null);
                    }
                    Payment.ExpireResult outcome = payment.expire(now, properties.processingGrace());
                    store.save(payment);
                    return outcome;
                });
                if (result.changed()) {
                    expired++;
                }
                if (result.voidAttemptId() != null) {
                    outcomes.performVoid(paymentId, result.voidAttemptId());
                }
            } catch (RuntimeException e) {
                log.warn("Expiry of payment {} failed; will retry", paymentId, e);
            }
        }
        return expired;
    }
}
