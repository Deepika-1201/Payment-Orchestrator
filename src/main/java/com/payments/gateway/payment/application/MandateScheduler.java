package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.Failure;
import com.payments.gateway.payment.domain.Mandate;
import com.payments.gateway.payment.domain.MandateCustomer;
import com.payments.gateway.payment.domain.MandateDebit;
import com.payments.gateway.payment.domain.MandateDebitStatus;
import com.payments.gateway.payment.domain.MandateStatus;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.MandateRepository;
import com.payments.gateway.payment.infrastructure.MandateRepository.ClaimedDebit;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.spi.MandateRequests.DebitNotificationQuery;
import com.payments.gateway.provider.spi.MandateRequests.DebitNotificationRequest;
import com.payments.gateway.provider.spi.MandateRequests.ExecuteDebitRequest;
import com.payments.gateway.provider.spi.ProviderCredentialsException;
import com.payments.gateway.provider.spi.ProviderNotificationResult;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.config.WorkerProperties;
import com.payments.gateway.shared.model.FailureCategory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The mandate worker (LLD §18.4, §18.5): polls registrations and expires mandates, then moves each debit through
 * notification and execution. Rows are claimed with a lease, so any number of replicas can run it.
 */
@Component
public class MandateScheduler {

    private static final Logger log = LoggerFactory.getLogger(MandateScheduler.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration UNAVAILABLE_RETRY = Duration.ofMinutes(1);

    private record Started(Mandate mandate, MandateDebit debit, String attemptId) {
    }

    private final MandateRepository mandates;
    private final PaymentRepository payments;
    private final MandateService service;
    private final PaymentStore store;
    private final PaymentOutcomeService outcomes;
    private final ProviderClient providerClient;
    private final MandateProperties properties;
    private final WorkerProperties workers;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MandateScheduler(MandateRepository mandates, PaymentRepository payments, MandateService service,
                            PaymentStore store, PaymentOutcomeService outcomes, ProviderClient providerClient,
                            MandateProperties properties, WorkerProperties workers, TransactionTemplate tx, Clock clock) {
        this.mandates = mandates;
        this.payments = payments;
        this.service = service;
        this.store = store;
        this.outcomes = outcomes;
        this.providerClient = providerClient;
        this.properties = properties;
        this.workers = workers;
        this.tx = tx;
        this.clock = clock;
    }

    public int processDueMandates() {
        Instant now = clock.instant();
        List<String> due = mandates.claimDueMandates(now, now.plus(LEASE), workers.batchSize());
        for (String mandateId : due) {
            try {
                service.check(mandateId);
            } catch (RuntimeException e) {
                log.error("Check of mandate {} failed; retrying after the lease", mandateId, e);
            }
        }
        return due.size();
    }

    public int processDueDebits() {
        Instant now = clock.instant();
        List<ClaimedDebit> due = mandates.claimDueDebits(now, now.plus(LEASE), workers.batchSize());
        for (ClaimedDebit claimed : due) {
            try {
                advance(claimed);
            } catch (RuntimeException e) {
                log.error("Debit {} could not advance; retrying after the lease", claimed.id(), e);
            }
        }
        return due.size();
    }

    private void advance(ClaimedDebit claimed) {
        MandateDebit debit = mandates.findDebitById(claimed.id()).orElseThrow();
        Mandate mandate = mandates.findById(claimed.mandateId()).orElseThrow();
        switch (debit.status()) {
            case SCHEDULED, NOTIFYING -> notifyOrCheck(mandate, debit);
            case READY -> execute(claimed);
            default -> {
            }
        }
    }

    /** Requests the cycle's pre-debit notification (SCHEDULED) or asks whether it was delivered (NOTIFYING). */
    private void notifyOrCheck(Mandate mandate, MandateDebit debit) {
        Instant now = clock.instant();
        if (mandate.status() != MandateStatus.ACTIVE) {
            endUnexecuted(debit, mandateEnded(mandate), "The mandate is " + wire(mandate.status()), now);
            return;
        }
        if (debit.notificationOverdue(properties.notificationTimeout(), now)) {
            endUnexecuted(debit, "notification_not_delivered",
                    "The PSP did not confirm the pre-debit notification in time", now);
            return;
        }
        ProviderNotificationResult result;
        try {
            result = debit.status() == MandateDebitStatus.SCHEDULED
                    ? providerClient.notifyDebit(mandate.merchantId(), mandate.providerCode(),
                            new DebitNotificationRequest(debit.notificationId(), debit.id(), mandate.id(),
                                    mandate.instrument(), mandate.providerMandateReference(),
                                    mandate.providerCustomerReference(), debit.amount(), debit.notBefore(),
                                    debit.description()))
                    : providerClient.fetchDebitNotification(mandate.merchantId(), mandate.providerCode(),
                            new DebitNotificationQuery(debit.notificationId(), debit.notificationReference()));
        } catch (ProviderUnavailableException | ProviderTimeoutException e) {
            log.warn("Notification of debit {} not confirmed ({}); asking again later", debit.id(), e.getMessage());
            result = null;
        }
        ProviderNotificationResult outcome = result;
        TransitionSource source = debit.status() == MandateDebitStatus.SCHEDULED ? TransitionSource.PROVIDER_RESPONSE
                : TransitionSource.STATUS_CHECK;
        tx.executeWithoutResult(status -> {
            Payment payment = payments.lockById(debit.paymentId()).orElseThrow();
            MandateDebit locked = mandates.lockDebitByPayment(payment.id()).orElseThrow();
            if (locked.status() != debit.status() || locked.cycle() != debit.cycle()) {
                return;
            }
            Instant checkAt = now.plus(properties.notificationCheckInterval());
            if (outcome == null) {
                locked.checkAgainAt(checkAt, now);
                store.saveDebit(locked);
                return;
            }
            switch (outcome.status()) {
                case DELIVERED -> {
                    locked.notificationDelivered(outcome.providerReference(),
                            outcome.deliveredAt() == null ? now : outcome.deliveredAt(), properties.notificationLead(),
                            source, now);
                    store.saveDebit(locked);
                }
                case FAILED -> service.failWaitingPayment(payment, locked, "notification_failed",
                        outcome.failure() == null ? "The PSP could not deliver the pre-debit notification"
                                : outcome.failure().message(), source, now);
                case PENDING -> {
                    if (locked.status() == MandateDebitStatus.SCHEDULED) {
                        locked.notificationRequested(outcome.providerReference(), properties.notificationCheckInterval(),
                                now);
                    } else {
                        locked.checkAgainAt(checkAt, now);
                    }
                    store.saveDebit(locked);
                }
                case NOT_FOUND -> {
                    locked.checkAgainAt(checkAt, now);
                    store.saveDebit(locked);
                }
            }
        });
    }

    /**
     * Starts the cycle's attempt under the mandate's shared lock, so a revocation (which takes it exclusively) either
     * sees the debit executing or cancels it first; then asks the PSP to debit, outside the transaction.
     */
    private void execute(ClaimedDebit claimed) {
        Instant now = clock.instant();
        String attemptId = Ids.newId("att");
        Started started = tx.execute(status -> {
            Mandate mandate = mandates.lockForShare(claimed.mandateId()).orElseThrow();
            Payment payment = payments.lockById(claimed.paymentId()).orElseThrow();
            MandateDebit debit = mandates.lockDebitByPayment(payment.id()).orElseThrow();
            if (debit.status() != MandateDebitStatus.READY || payment.status() != PaymentStatus.REQUIRES_PAYMENT_METHOD) {
                return null;
            }
            if (mandate.status() != MandateStatus.ACTIVE) {
                service.failWaitingPayment(payment, debit, mandateEnded(mandate), "The mandate is " + wire(mandate.status()),
                        TransitionSource.SYSTEM, now);
                return null;
            }
            if (!providerClient.isAvailable(mandate.providerCode())) {
                debit.checkAgainAt(now.plus(UNAVAILABLE_RETRY), now);
                store.saveDebit(debit);
                return null;
            }
            debit.executionStarted(now);
            store.saveDebit(debit);
            payment.startMandateAttempt(attemptId, mandate.providerCode(), debit.notificationReference(), now);
            store.save(payment);
            return new Started(mandate, debit, attemptId);
        });
        if (started == null) {
            return;
        }
        Mandate mandate = started.mandate();
        MandateDebit debit = started.debit();
        MandateCustomer customer = mandate.customer();
        try {
            ProviderPaymentResult result = providerClient.executeDebit(mandate.merchantId(), mandate.providerCode(),
                    new ExecuteDebitRequest(attemptId, debit.id(), mandate.id(), mandate.instrument(),
                            mandate.providerMandateReference(), mandate.providerCustomerReference(),
                            debit.notificationReference(), debit.amount(), debit.description(), customer.email(),
                            customer.phone()));
            outcomes.apply(debit.paymentId(), attemptId, ProviderResults.toUpdate(result),
                    TransitionSource.PROVIDER_RESPONSE);
        } catch (ProviderUnavailableException e) {
            Failure failure = e instanceof ProviderCredentialsException
                    ? new Failure("provider_credentials_rejected", FailureCategory.VALIDATION, e.getMessage())
                    : new Failure("provider_unavailable", FailureCategory.PROVIDER_UNAVAILABLE, e.getMessage());
            outcomes.failover(debit.paymentId(), attemptId, null, failure);
        } catch (ProviderTimeoutException e) {
            log.warn("Debit {} attempt {} timed out; the status resolver will look it up", debit.id(), attemptId);
            outcomes.apply(debit.paymentId(), attemptId, AttemptUpdate.of(AttemptStatus.UNKNOWN),
                    TransitionSource.PROVIDER_RESPONSE);
        }
    }

    /** Ends a debit that never executed by failing its payment, which the debit then follows. */
    private void endUnexecuted(MandateDebit debit, String code, String message, Instant now) {
        tx.executeWithoutResult(status -> {
            Payment payment = payments.lockById(debit.paymentId()).orElseThrow();
            MandateDebit locked = mandates.lockDebitByPayment(payment.id()).orElseThrow();
            service.failWaitingPayment(payment, locked, code, message, TransitionSource.SYSTEM, now);
        });
    }

    private static String mandateEnded(Mandate mandate) {
        return "mandate_" + mandate.status().name().toLowerCase(Locale.ROOT);
    }

    private static String wire(MandateStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }
}
