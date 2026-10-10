package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.CreditAllocation;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.PaymentPolicy;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.payment.domain.TransferCredit;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository.AttemptLocator;
import com.payments.gateway.payment.infrastructure.TransferCreditRepository;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.spi.ProviderCredit;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderRequests.CloseCollectionRequest;
import com.payments.gateway.provider.spi.ProviderRequests.CreditsQuery;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.MethodType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bank transfer credits (ADR-038, LLD §21): records each credit once, allocates it under the payment's lock, sends
 * back what does not pay for anything, and settles payments still waiting at expiry.
 */
@Service
public class TransferCreditService {

    private static final Logger log = LoggerFactory.getLogger(TransferCreditService.class);

    private record Outcome(String paymentId, String merchantId, PaymentAttempt attempt, List<String> returns,
                           boolean ended) {
    }

    private final PaymentRepository payments;
    private final TransferCreditRepository credits;
    private final PaymentStore store;
    private final RefundService refunds;
    private final PaymentOutcomeService outcomes;
    private final ProviderClient providerClient;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public TransferCreditService(PaymentRepository payments, TransferCreditRepository credits, PaymentStore store,
                                 RefundService refunds, PaymentOutcomeService outcomes, ProviderClient providerClient,
                                 PaymentProperties properties, TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.credits = credits;
        this.store = store;
        this.refunds = refunds;
        this.outcomes = outcomes;
        this.providerClient = providerClient;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
        for (String result : List.of("applied", "returned", "unmatched")) {
            meters.counter("pg.transfer.credits", "result", result);
        }
    }

    /** A credit reported by webhook; {@code merchantScope} as in {@link PaymentOutcomeService#applyProviderEvent}. */
    public boolean applyProviderEvent(String providerCode, String merchantScope, ProviderEvent event) {
        return record(providerCode, merchantScope, event.credit(), TransitionSource.PROVIDER_WEBHOOK);
    }

    /** True when the payment's latest attempt is a bank transfer still waiting for credits. */
    public static boolean awaitingTransfer(Payment payment) {
        PaymentAttempt latest = payment.latestAttempt();
        return latest != null && latest.method().type() == MethodType.BANK_TRANSFER
                && latest.status() == AttemptStatus.REQUIRES_ACTION && payment.status() == PaymentStatus.REQUIRES_ACTION;
    }

    /** Status check of a waiting bank transfer attempt: records the credits the account holds (LLD §21.3). */
    public void poll(String paymentId, String attemptId) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        PaymentAttempt attempt = payment.attempt(attemptId).orElseThrow();
        try {
            fetchAndRecord(payment.merchantId(), attempt);
        } catch (ProviderTimeoutException | ProviderUnavailableException e) {
            log.warn("Credits of attempt {} could not be fetched; the next check retries", attemptId);
        }
        outcomes.recordCheck(paymentId, attemptId);
    }

    /**
     * Expires a payment waiting for a bank transfer (LLD §21.3), for the expiry job: fetches the account's credits
     * first (best effort), then accepts a short payment or expires it and sends its credits back. Returns whether the
     * payment changed.
     */
    public boolean expire(String paymentId) {
        Instant now = clock.instant();
        Payment current = payments.findById(paymentId).orElseThrow();
        PaymentAttempt waiting = current.latestAttempt();
        try {
            fetchAndRecord(current.merchantId(), waiting);
        } catch (ProviderTimeoutException | ProviderUnavailableException e) {
            log.warn("Credits of attempt {} could not be fetched before expiry; later ones go back as late", waiting.id());
        }
        Outcome outcome = tx.execute(status -> {
            Payment payment = payments.lockById(paymentId).orElseThrow();
            if (!awaitingTransfer(payment)) {
                return null;
            }
            PaymentPolicy policy = outcomes.policyFor(payment);
            long received = credits.sumApplied(waiting.id());
            List<String> returns = new ArrayList<>();
            if (received > 0 && policy.acceptShortTransfer()) {
                payment.acceptShortTransfer(waiting.id(), received, policy, now);
                log.info("Payment {} accepted short at expiry: {} of {}", paymentId, received, payment.amount().amount());
            } else {
                payment.expire(now, properties.processingGrace());
                payment.applyAttemptUpdate(waiting.id(), AttemptUpdate.failed("transfer_not_completed",
                        FailureCategory.CUSTOMER, "No transfer completed the payment before it expired"),
                        TransitionSource.SYSTEM, policy, now);
                for (TransferCredit credit : credits.findByAttempt(waiting.id())) {
                    if (credit.appliedAmount() > 0) {
                        credit.releaseApplied(now);
                        returns.add(sendBack(payment, credit, CreditAllocation.SHORT_AT_EXPIRY, now));
                        store.saveCredit(credit);
                    }
                }
            }
            store.save(payment);
            return new Outcome(paymentId, payment.merchantId(), waiting, returns, true);
        });
        if (outcome == null) {
            return false;
        }
        followUp(outcome);
        return true;
    }

    public List<TransferCredit> listForPayment(String merchantId, String paymentId) {
        payments.findForMerchant(merchantId, paymentId).orElseThrow(() -> GatewayException.notFound("Payment", paymentId));
        return credits.findByPayment(paymentId);
    }

    private void fetchAndRecord(String merchantId, PaymentAttempt attempt) {
        List<ProviderCredit> found = providerClient.fetchCredits(merchantId, attempt.providerCode(),
                new CreditsQuery(attempt.id(), attempt.providerReference()));
        for (ProviderCredit credit : found) {
            record(attempt.providerCode(), merchantId, credit, TransitionSource.STATUS_CHECK);
        }
    }

    private boolean record(String providerCode, String merchantScope, ProviderCredit reported, TransitionSource source) {
        Optional<AttemptLocator> locator = locate(providerCode, reported);
        if (locator.isEmpty()) {
            return recordUnmatched(providerCode, merchantScope, reported);
        }
        if (merchantScope != null && !merchantScope.equals(locator.get().merchantId())) {
            log.warn("Ignored {} credit {} from merchant {}'s account: attempt {} belongs to another merchant",
                    providerCode, reported.providerReference(), merchantScope, locator.get().attemptId());
            return false;
        }
        Instant now = clock.instant();
        Outcome outcome = tx.execute(status -> {
            Payment payment = payments.lockById(locator.get().paymentId()).orElseThrow();
            if (credits.findByProviderReference(providerCode, reported.providerReference()).isPresent()) {
                return null;
            }
            PaymentAttempt attempt = payment.attempt(locator.get().attemptId()).orElseThrow();
            CreditAllocation allocation = payment.allocateCredit(attempt.id(), reported.amount(),
                    credits.sumApplied(attempt.id()), outcomes.policyFor(payment), source, now);
            TransferCredit credit = TransferCredit.received(Ids.newId("trc"), payment.merchantId(), providerCode,
                    reported.providerReference(), reported.collectionReference(), attempt.id(), payment.id(),
                    reported.amount(), mode(reported.mode()), reported.utr(), reported.receivedAt(), now);
            credit.apply(allocation.applied(), now);
            List<String> returns = allocation.returned() > 0
                    ? List.of(sendBack(payment, credit, allocation.returnReason(), now)) : List.of();
            store.saveCredit(credit);
            store.save(payment);
            meters.counter("pg.transfer.credits", "result", allocation.applied() > 0 ? "applied" : "returned").increment();
            return new Outcome(payment.id(), payment.merchantId(), attempt, returns, allocation.funded());
        });
        if (outcome != null) {
            followUp(outcome);
        }
        return true;
    }

    /** Creates the refund that sends back what the credit did not apply; the caller saves the credit, under the lock. */
    private String sendBack(Payment payment, TransferCredit credit, String reason, Instant now) {
        String refundId = Ids.newId("rfnd");
        long amount = credit.returnUnapplied(refundId, now);
        refunds.createCreditReturn(payment, credit, refundId, amount, reason, now);
        return refundId;
    }

    private boolean recordUnmatched(String providerCode, String merchantScope, ProviderCredit reported) {
        if (merchantScope == null) {
            log.warn("Ignored {} credit {} to unknown account {}: no merchant account to record it under",
                    providerCode, reported.providerReference(), reported.collectionReference());
            return false;
        }
        Instant now = clock.instant();
        try {
            tx.executeWithoutResult(status -> store.saveCredit(TransferCredit.unmatched(Ids.newId("trc"), merchantScope,
                    providerCode, reported.providerReference(), reported.collectionReference(), reported.amount(),
                    mode(reported.mode()), reported.utr(), reported.receivedAt(), now)));
        } catch (DuplicateKeyException e) {
            return true;
        }
        meters.counter("pg.transfer.credits", "result", "unmatched").increment();
        log.error("{} credit {} of {} to unknown account {} queued for review", providerCode, reported.providerReference(),
                reported.amount(), reported.collectionReference());
        return true;
    }

    private Optional<AttemptLocator> locate(String providerCode, ProviderCredit reported) {
        Optional<AttemptLocator> locator = reported.collectionReference() == null ? Optional.empty()
                : payments.findAttemptByProviderReference(providerCode, reported.collectionReference());
        if (locator.isEmpty() && reported.merchantReference() != null) {
            locator = payments.findAttemptById(reported.merchantReference())
                    .filter(l -> l.providerCode().equals(providerCode));
        }
        return locator;
    }

    /** After commit: send the returns, and close the account once the attempt no longer waits for credits. */
    private void followUp(Outcome outcome) {
        outcome.returns().forEach(refunds::submit);
        if (outcome.ended()) {
            try {
                providerClient.closeCollection(outcome.merchantId(), outcome.attempt().providerCode(),
                        new CloseCollectionRequest(outcome.attempt().id(), outcome.attempt().providerReference()));
            } catch (RuntimeException e) {
                log.warn("Account {} of attempt {} not closed; the PSP closes it at expiry",
                        outcome.attempt().providerReference(), outcome.attempt().id(), e);
            }
        }
    }

    private static String mode(String reported) {
        if (reported == null) {
            return null;
        }
        String mode = reported.toUpperCase(Locale.ROOT);
        return List.of("NEFT", "RTGS", "IMPS", "UPI").contains(mode) ? mode : null;
    }
}
