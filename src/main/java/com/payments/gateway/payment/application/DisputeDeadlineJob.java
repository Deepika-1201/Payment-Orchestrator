package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.Dispute;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.infrastructure.DisputeRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells merchants that a dispute's evidence is due (FR-D3, LLD §22.4): {@code dispute.evidence_due}, once per dispute,
 * when its deadline is within the notice window and no response is pending or sent. The gauge counts every such
 * dispute, notified or not, for the operator alert.
 */
@Component
public class DisputeDeadlineJob {

    private static final Logger log = LoggerFactory.getLogger(DisputeDeadlineJob.class);

    private final PaymentRepository payments;
    private final DisputeRepository disputes;
    private final PaymentStore store;
    private final DisputeProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DisputeDeadlineJob(PaymentRepository payments, DisputeRepository disputes, PaymentStore store,
                              DisputeProperties properties, TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.disputes = disputes;
        this.store = store;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        Gauge.builder("pg.disputes.evidence_due", disputes,
                        repository -> repository.countEvidenceDue(clock.instant().plus(properties.evidenceDueNotice())))
                .description("Open disputes due within the notice window with no response pending or sent")
                .register(meters);
    }

    /** Returns how many disputes were notified. */
    public int notifyDue() {
        int batchSize = properties.noticeBatch();
        int notified = 0;
        while (true) {
            Instant now = clock.instant();
            List<String> batch = disputes.findEvidenceDueToNotify(now.plus(properties.evidenceDueNotice()), batchSize);
            int before = notified;
            for (String disputeId : batch) {
                if (notify(disputeId, now)) {
                    notified++;
                }
            }
            // A batch that notified nothing would come back unchanged: stop rather than loop.
            if (batch.size() < batchSize || notified == before) {
                return notified;
            }
        }
    }

    public List<Dispute> listDue(String merchantId, int limit) {
        return disputes.findEvidenceDue(clock.instant().plus(properties.evidenceDueNotice()), merchantId, limit);
    }

    private boolean notify(String disputeId, Instant now) {
        Dispute notified = tx.execute(status -> {
            Payment payment = payments.lockById(disputes.findById(disputeId).orElseThrow().paymentId()).orElseThrow();
            Dispute dispute = disputes.findById(disputeId).orElseThrow();
            if (!dispute.notifyEvidenceDue(now, properties.evidenceDueNotice())) {
                return null;
            }
            store.saveDispute(payment, dispute);
            return dispute;
        });
        if (notified == null) {
            return false;
        }
        log.warn("Evidence for dispute {} of merchant {} is due by {}", disputeId, notified.merchantId(), notified.respondBy());
        return true;
    }
}
