package com.payments.gateway.payment.application;

import com.payments.gateway.merchant.Merchant;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.AttemptUpdate;
import com.payments.gateway.payment.domain.Customer;
import com.payments.gateway.payment.domain.Failure;
import com.payments.gateway.payment.domain.Mandate;
import com.payments.gateway.payment.domain.MandateCustomer;
import com.payments.gateway.payment.domain.MandateDebit;
import com.payments.gateway.payment.domain.MandateDebitStatus;
import com.payments.gateway.payment.domain.MandateStatus;
import com.payments.gateway.payment.domain.MandateUpdate;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.MandateRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.MandateRequests.CreateMandateRequest;
import com.payments.gateway.provider.spi.MandateRequests.MandateQuery;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.provider.spi.ProviderCapabilities.MandateSupport;
import com.payments.gateway.provider.spi.ProviderCredentialsException;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderMandateResult;
import com.payments.gateway.provider.spi.ProviderNotificationResult;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.MandateFrequency;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.web.WireEnums;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mandates and their debits (ADR-035, LLD §18): registration, revocation, PSP evidence and the merchant's debit
 * requests. Transactions never span a PSP call; lock order is mandate → payment → debit.
 */
@Service
public class MandateService {

    private static final Logger log = LoggerFactory.getLogger(MandateService.class);
    private static final Duration START_TOLERANCE = Duration.ofHours(1);
    private static final Duration MAX_TERM = Duration.ofDays(30 * 366L);

    public record CreateCommand(MandateInstrument instrument, Money maxAmount, MandateFrequency frequency, Instant startAt,
                                Instant endAt, String description, MandateCustomer customer,
                                Map<String, String> metadata, String returnUrl) {
    }

    public record DebitCommand(long amount, String merchantDebitId, Instant dueAt, String description) {
    }

    private record Created(Mandate mandate, String registrationPaymentId, String registrationAttemptId) {
    }

    private final MandateRepository mandates;
    private final PaymentRepository payments;
    private final PaymentStore store;
    private final PaymentOutcomeService outcomes;
    private final MerchantDirectory merchants;
    private final ProviderRegistry registry;
    private final ProviderClient providerClient;
    private final MandateProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public MandateService(MandateRepository mandates, PaymentRepository payments, PaymentStore store,
                          PaymentOutcomeService outcomes, MerchantDirectory merchants, ProviderRegistry registry,
                          ProviderClient providerClient, MandateProperties properties, TransactionTemplate tx,
                          Clock clock, MeterRegistry meters) {
        this.mandates = mandates;
        this.payments = payments;
        this.store = store;
        this.outcomes = outcomes;
        this.merchants = merchants;
        this.registry = registry;
        this.providerClient = providerClient;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
        for (String result : List.of("processed", "ignored", "foreign_resource")) {
            meters.counter("pg.mandates.provider_events", "result", result);
        }
    }

    // ---------------------------------------------------------------- mandates

    public Mandate create(String merchantId, CreateCommand command) {
        Instant now = clock.instant();
        Money maxAmount = command.maxAmount();
        if (!maxAmount.inSettlementCurrency()) {
            throw new GatewayException(ErrorCode.UNSUPPORTED_CURRENCY, "Mandates are in " + Money.SETTLEMENT_CURRENCY);
        }
        merchants.require(merchantId);
        Instant startAt = command.startAt() != null ? command.startAt() : now;
        if (startAt.isBefore(now.minus(START_TOLERANCE))) {
            throw GatewayException.validation("start_at", "must not be in the past");
        }
        Instant endAt = command.endAt() != null ? command.endAt() : startAt.plus(properties.defaultTerm());
        if (endAt.isAfter(now.plus(MAX_TERM))) {
            throw GatewayException.validation("end_at", "must be within 30 years");
        }
        PaymentProvider provider = chooseProvider(merchantId, command.instrument(), maxAmount);
        MandateSupport support = provider.capabilities().mandates().get(command.instrument());
        Instant authorizationExpiresAt = now.plus(properties.authorizationWindow());
        Created created = tx.execute(status -> {
            Mandate mandate = Mandate.create(Ids.newId("mdt"), merchantId, provider.code(), command.instrument(), maxAmount,
                    command.frequency(), startAt, endAt, command.description(), command.customer(), command.metadata(),
                    authorizationExpiresAt, now);
            Payment registration = null;
            if (support.registrationAmount() > 0) {
                registration = Payment.createForMandate(Ids.newId("pay"), merchantId, mandate.id(),
                        Money.of(support.registrationAmount(), maxAmount.currency()), "Mandate authorization",
                        customerOf(mandate), Map.of(), authorizationExpiresAt, mandate.id(), 1, now);
                mandate.linkRegistrationPayment(registration.id(), now);
            }
            store.saveMandate(mandate);
            if (registration == null) {
                return new Created(mandate, null, null);
            }
            PaymentAttempt attempt = registration.startMandateAttempt(Ids.newId("att"), provider.code(), null, now);
            store.save(registration);
            return new Created(mandate, registration.id(), attempt.id());
        });
        Mandate mandate = created.mandate();
        MandateCustomer customer = mandate.customer();
        CreateMandateRequest request = new CreateMandateRequest(mandate.id(), merchantId, mandate.instrument(), maxAmount,
                mandate.frequency(), startAt, endAt, authorizationExpiresAt, mandate.description(), customer.name(),
                customer.email(), customer.phone(), created.registrationAttemptId(),
                support.registrationAmount() > 0 ? Money.of(support.registrationAmount(), maxAmount.currency()) : null,
                command.returnUrl());
        meters.counter("pg.mandates", "instrument", mandate.instrument().name().toLowerCase(Locale.ROOT)).increment();
        try {
            ProviderMandateResult result = providerClient.createMandate(merchantId, provider.code(), request);
            return applyResult(mandate.id(), result, TransitionSource.PROVIDER_RESPONSE);
        } catch (ProviderUnavailableException e) {
            Failure failure = e instanceof ProviderCredentialsException
                    ? new Failure("provider_credentials_rejected", FailureCategory.VALIDATION, e.getMessage())
                    : new Failure("provider_unavailable", FailureCategory.PROVIDER_UNAVAILABLE, e.getMessage());
            if (created.registrationAttemptId() != null) {
                outcomes.failover(created.registrationPaymentId(), created.registrationAttemptId(), null, failure);
            }
            return applyUpdate(mandate.id(), MandateUpdate.failed(failure), TransitionSource.PROVIDER_RESPONSE, false);
        } catch (ProviderTimeoutException e) {
            log.warn("Registration of mandate {} timed out; the poller will look it up", mandate.id());
            return mandates.findById(mandate.id()).orElseThrow();
        }
    }

    public Mandate get(String merchantId, String mandateId) {
        return mandates.findForMerchant(merchantId, mandateId)
                .orElseThrow(() -> GatewayException.notFound("Mandate", mandateId));
    }

    /** Revokes at the PSP first, then here; a revoked mandate replays as success (LLD §18.2). */
    public Mandate revoke(String merchantId, String mandateId) {
        Mandate current = get(merchantId, mandateId);
        if (current.status() == MandateStatus.REVOKED) {
            return current;
        }
        if (current.status().isFinal()) {
            throw new GatewayException(ErrorCode.MANDATE_INVALID_STATE, "Cannot revoke a mandate in status " + current.status());
        }
        try {
            providerClient.revokeMandate(merchantId, current.providerCode(), query(current));
        } catch (ProviderUnavailableException | ProviderTimeoutException e) {
            throw new GatewayException(ErrorCode.NO_PROVIDER_AVAILABLE,
                    "The PSP could not confirm the revocation; retry with the same Idempotency-Key", List.of(), 5);
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            Mandate mandate = mandates.lockById(mandateId).orElseThrow();
            if (mandate.revoke(TransitionSource.API, "requested_by_merchant", now)) {
                endWaitingWork(mandate, "mandate_revoked", now);
            }
            store.saveMandate(mandate);
            return mandate;
        });
    }

    /** Applies a mandate webhook; returns false when no mandate matches (the inbox marks it ignored). */
    public boolean applyProviderEvent(String providerCode, String merchantScope, ProviderEvent event) {
        ProviderMandateResult result = event.mandate();
        Optional<String> mandateId = Optional.empty();
        for (String reference : new String[] {result.providerReference(), result.providerMandateReference()}) {
            if (mandateId.isEmpty() && reference != null) {
                mandateId = mandates.findIdByProviderReference(providerCode, reference, merchantScope);
            }
        }
        if (mandateId.isEmpty() && event.merchantReference() != null) {
            Optional<Mandate> byOurId = mandates.findById(event.merchantReference())
                    .filter(m -> m.providerCode().equals(providerCode));
            if (byOurId.isPresent() && merchantScope != null && !merchantScope.equals(byOurId.get().merchantId())) {
                log.warn("Ignored {} mandate event {} from merchant {}'s account: mandate {} belongs to another merchant",
                        providerCode, event.eventId(), merchantScope, byOurId.get().id());
                meters.counter("pg.mandates.provider_events", "result", "foreign_resource").increment();
                return false;
            }
            mandateId = byOurId.map(Mandate::id);
        }
        if (mandateId.isEmpty()) {
            meters.counter("pg.mandates.provider_events", "result", "ignored").increment();
            return false;
        }
        applyResult(mandateId.get(), result, TransitionSource.PROVIDER_WEBHOOK);
        meters.counter("pg.mandates.provider_events", "result", "processed").increment();
        return true;
    }

    /**
     * Expires a mandate at its end; otherwise asks the PSP for its status: a registration awaiting the customer, or a
     * live mandate whose debit just failed (a revocation or pause whose webhook never arrived).
     */
    void check(String mandateId) {
        Instant now = clock.instant();
        Mandate current = mandates.findById(mandateId).orElse(null);
        if (current == null || current.status().isFinal()) {
            return;
        }
        boolean live = current.status() == MandateStatus.ACTIVE || current.status() == MandateStatus.PAUSED;
        if (live && !now.isBefore(current.endAt())) {
            tx.executeWithoutResult(status -> {
                Mandate mandate = mandates.lockById(mandateId).orElseThrow();
                if (mandate.expireIfDue(now)) {
                    endWaitingWork(mandate, "mandate_expired", now);
                }
                mandate.recordCheck(now);
                store.saveMandate(mandate);
            });
            return;
        }
        ProviderMandateResult result;
        try {
            result = providerClient.fetchMandate(current.merchantId(), current.providerCode(), query(current));
        } catch (ProviderUnavailableException | ProviderTimeoutException e) {
            result = null;
        }
        MandateUpdate update = result == null ? new MandateUpdate(null, null, null, null, null, null) : toUpdate(result);
        if (result != null && result.status() == ProviderMandateResult.Status.NOT_FOUND
                && current.status() == MandateStatus.CREATED) {
            update = MandateUpdate.failed(new Failure("not_submitted", FailureCategory.NOT_SUBMITTED,
                    "The PSP never received the registration"));
        }
        MandateUpdate applied = update;
        boolean[] lapsed = {false};
        Mandate mandate = tx.execute(status -> {
            Mandate locked = mandates.lockById(mandateId).orElseThrow();
            boolean changed = locked.apply(applied, TransitionSource.STATUS_CHECK, now);
            if (!locked.status().isFinal() && locked.authorizationLapsed(now)) {
                lapsed[0] = locked.apply(MandateUpdate.failed(new Failure("authorization_expired", FailureCategory.CUSTOMER,
                        "The customer did not authorize the mandate in time")), TransitionSource.SYSTEM, now);
                changed |= lapsed[0];
            }
            if (changed && locked.status().isFinal()) {
                endWaitingWork(locked, "mandate_" + locked.status().name().toLowerCase(Locale.ROOT), now);
            }
            locked.recordCheck(now);
            store.saveMandate(locked);
            return locked;
        });
        if (result != null) {
            updateRegistrationAttempt(mandate, result);
        }
        if (lapsed[0]) {
            try {
                providerClient.revokeMandate(mandate.merchantId(), mandate.providerCode(), query(mandate));
            } catch (RuntimeException e) {
                log.warn("Could not cancel the lapsed registration of mandate {} at {}", mandateId, mandate.providerCode(), e);
            }
        }
    }

    // ---------------------------------------------------------------- debits

    public MandateDebit createDebit(String merchantId, String mandateId, DebitCommand command) {
        Instant now = clock.instant();
        Merchant merchant = merchants.require(merchantId);
        long frictionlessLimit = merchant.mandateDebitLimit() != null ? merchant.mandateDebitLimit()
                : properties.frictionlessDebitLimit();
        get(merchantId, mandateId);
        return tx.execute(status -> {
            Mandate mandate = mandates.lockById(mandateId).orElseThrow();
            if (mandates.findDebitByMerchantReference(mandateId, command.merchantDebitId()).isPresent()) {
                throw new GatewayException(ErrorCode.MANDATE_DEBIT_ALREADY_EXISTS,
                        "Debit " + command.merchantDebitId() + " already exists for mandate " + mandateId);
            }
            if (mandates.hasDebitInProgress(mandateId)) {
                throw new GatewayException(ErrorCode.MANDATE_INVALID_STATE,
                        "Another debit of this mandate is still in progress; wait for its outcome");
            }
            Instant dueAt = command.dueAt() != null ? command.dueAt()
                    : mandate.instrument().requiresDebitNotification()
                    ? now.plus(properties.notificationLead()).plus(Duration.ofHours(1)) : now;
            String paymentId = Ids.newId("pay");
            MandateDebit debit = mandate.newDebit(Ids.newId("mdd"), paymentId, command.merchantDebitId(),
                    Money.of(command.amount(), mandate.maxAmount().currency()), frictionlessLimit, dueAt,
                    command.description(), properties.notifyAhead(), now);
            Payment payment = Payment.createForMandate(paymentId, merchantId, command.merchantDebitId(), debit.amount(),
                    command.description(), customerOf(mandate), Map.of(), paymentExpiry(debit.notBefore(), now),
                    mandateId, properties.attemptLimit(), now);
            store.save(payment);
            store.saveDebit(debit);
            meters.counter("pg.mandates.debits", "instrument", mandate.instrument().name().toLowerCase(Locale.ROOT))
                    .increment();
            return debit;
        });
    }

    public MandateDebit getDebit(String merchantId, String mandateId, String debitId) {
        get(merchantId, mandateId);
        return mandates.findDebit(mandateId, debitId).orElseThrow(() -> GatewayException.notFound("Mandate debit", debitId));
    }

    public List<MandateDebit> listDebits(String merchantId, String mandateId) {
        get(merchantId, mandateId);
        return mandates.listDebits(mandateId);
    }

    /** Cancels a debit that has not started executing, and its payment (LLD §18.2). */
    public MandateDebit cancelDebit(String merchantId, String mandateId, String debitId) {
        MandateDebit current = getDebit(merchantId, mandateId, debitId);
        Instant now = clock.instant();
        return tx.execute(status -> {
            Payment payment = payments.lockById(current.paymentId()).orElseThrow();
            MandateDebit debit = mandates.lockDebitByPayment(payment.id()).orElseThrow();
            if (debit.status() == MandateDebitStatus.CANCELLED) {
                return debit;
            }
            if (!debit.cancel(TransitionSource.API, "requested_by_merchant", now)) {
                throw new GatewayException(ErrorCode.MANDATE_INVALID_STATE,
                        "Debit is " + WireEnums.wire(debit.status()) + " and can no longer be cancelled");
            }
            store.saveDebit(debit);
            payment.cancel("debit_cancelled", now);
            store.save(payment);
            return debit;
        });
    }

    /** Applies a pre-debit notification webhook to the debit's current cycle; false when none matches. */
    public boolean applyNotificationEvent(String providerCode, String merchantScope, ProviderEvent event) {
        ProviderNotificationResult result = event.notification();
        Optional<MandateRepository.ClaimedDebit> debit = result.providerReference() == null ? Optional.empty()
                : mandates.findDebitByNotificationReference(providerCode, result.providerReference(), merchantScope);
        if (debit.isEmpty()) {
            meters.counter("pg.mandates.provider_events", "result", "ignored").increment();
            return false;
        }
        Instant now = clock.instant();
        String reference = result.providerReference();
        tx.executeWithoutResult(status -> {
            Payment payment = payments.lockById(debit.get().paymentId()).orElseThrow();
            MandateDebit locked = mandates.lockDebitByPayment(payment.id()).orElseThrow();
            if (!reference.equals(locked.notificationReference())) {
                return;
            }
            switch (result.status()) {
                case DELIVERED -> {
                    if (locked.notificationDelivered(reference, result.deliveredAt(), properties.notificationLead(),
                            TransitionSource.PROVIDER_WEBHOOK, now)) {
                        store.saveDebit(locked);
                    }
                }
                case FAILED -> failWaitingPayment(payment, locked, "notification_failed",
                        result.failure() == null ? "The PSP could not deliver the pre-debit notification"
                                : result.failure().message(), TransitionSource.PROVIDER_WEBHOOK, now);
                default -> {
                }
            }
        });
        meters.counter("pg.mandates.provider_events", "result", "processed").increment();
        return true;
    }

    // ---------------------------------------------------------------- shared with the scheduler

    Mandate applyResult(String mandateId, ProviderMandateResult result, TransitionSource source) {
        Mandate mandate = applyUpdate(mandateId, toUpdate(result), source, source == TransitionSource.STATUS_CHECK);
        if (source == TransitionSource.PROVIDER_RESPONSE) {
            updateRegistrationAttempt(mandate, result);
        }
        return mandate;
    }

    /** Ends a waiting debit's payment, which ends the debit with it (LLD §18.5). */
    void failWaitingPayment(Payment payment, MandateDebit debit, String code, String message, TransitionSource source,
                            Instant now) {
        if (!debit.status().isWaiting() || payment.status() != PaymentStatus.REQUIRES_PAYMENT_METHOD) {
            return;
        }
        payment.fail(code, message, source, now);
        store.save(payment);
    }

    static MandateQuery query(Mandate mandate) {
        return new MandateQuery(mandate.id(), mandate.instrument(), mandate.providerReference(),
                mandate.providerMandateReference(), mandate.providerCustomerReference());
    }

    static Customer customerOf(Mandate mandate) {
        MandateCustomer customer = mandate.customer();
        return new Customer(customer.reference(), customer.email(), customer.phone());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A debit's payment stays open through every cycle: a pending attempt must never expire into an auto-refund. Each
     * cycle may wait a retry interval, the notification's delivery and the notice period.
     */
    private Instant paymentExpiry(Instant notBefore, Instant now) {
        Instant start = notBefore.isAfter(now) ? notBefore : now;
        Duration cycle = properties.retryInterval().plus(properties.notificationLead()).plus(properties.notificationTimeout());
        return start.plus(cycle.multipliedBy(properties.attemptLimit())).plus(Duration.ofDays(3));
    }

    private PaymentProvider chooseProvider(String merchantId, MandateInstrument instrument, Money maxAmount) {
        Set<String> linked = merchants.activeProviders(merchantId);
        List<PaymentProvider> capable = registry.all().stream()
                .filter(provider -> linked.contains(provider.code()))
                .filter(provider -> provider.capabilities().supportsMandate(instrument, maxAmount))
                .toList();
        if (capable.isEmpty()) {
            throw new GatewayException(ErrorCode.UNSUPPORTED_PAYMENT_METHOD, "No linked provider supports "
                    + WireEnums.wire(instrument) + " mandates up to this max_amount and currency");
        }
        return capable.stream().filter(provider -> providerClient.isAvailable(provider.code())).findFirst()
                .orElseThrow(() -> new GatewayException(ErrorCode.NO_PROVIDER_AVAILABLE,
                        "All providers for this instrument are temporarily unavailable", List.of(), 5));
    }

    private Mandate applyUpdate(String mandateId, MandateUpdate update, TransitionSource source, boolean recordCheck) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            Mandate mandate = mandates.lockById(mandateId).orElseThrow();
            boolean changed = mandate.apply(update, source, now);
            if (changed && (mandate.status() == MandateStatus.REVOKED || mandate.status() == MandateStatus.EXPIRED
                    || mandate.status() == MandateStatus.FAILED)) {
                endWaitingWork(mandate, "mandate_" + mandate.status().name().toLowerCase(Locale.ROOT), now);
            }
            if (recordCheck) {
                mandate.recordCheck(now);
            }
            store.saveMandate(mandate);
            return mandate;
        });
    }

    /**
     * A mandate that ended cancels its debits that have not started executing, and a registration charge the customer
     * has not paid yet. Runs under the mandate lock; takes each payment's lock, then its debit's.
     */
    private void endWaitingWork(Mandate mandate, String reason, Instant now) {
        for (String paymentId : mandates.waitingDebitPayments(mandate.id())) {
            Payment payment = payments.lockById(paymentId).orElseThrow();
            MandateDebit debit = mandates.lockDebitByPayment(paymentId).orElseThrow();
            if (debit.cancel(TransitionSource.SYSTEM, reason, now)) {
                store.saveDebit(debit);
            }
            if (payment.status() == PaymentStatus.REQUIRES_PAYMENT_METHOD) {
                payment.cancel(reason, now);
                store.save(payment);
            }
        }
        if (mandate.registrationPaymentId() != null) {
            payments.lockById(mandate.registrationPaymentId())
                    .filter(payment -> payment.status() == PaymentStatus.REQUIRES_ACTION
                            || payment.status() == PaymentStatus.REQUIRES_PAYMENT_METHOD)
                    .ifPresent(payment -> {
                        payment.cancel(reason, now);
                        store.save(payment);
                    });
        }
    }

    /** The registration charge's attempt learns its PSP reference and the customer's next action from the response. */
    private void updateRegistrationAttempt(Mandate mandate, ProviderMandateResult result) {
        if (mandate.registrationPaymentId() == null) {
            return;
        }
        Payment payment = payments.findById(mandate.registrationPaymentId()).orElse(null);
        PaymentAttempt attempt = payment == null ? null : payment.latestAttempt();
        if (attempt == null || attempt.status() != AttemptStatus.INITIATED) {
            return;
        }
        AttemptUpdate update = switch (result.status()) {
            case PENDING -> result.nextAction() == null && result.registrationPaymentReference() == null ? null
                    : new AttemptUpdate(AttemptStatus.REQUIRES_ACTION, result.registrationPaymentReference(),
                            result.nextAction(), null, null);
            case FAILED -> new AttemptUpdate(AttemptStatus.FAILED, result.registrationPaymentReference(), null,
                    ProviderResults.toFailure(result.failure()), null);
            default -> null;
        };
        if (update != null) {
            outcomes.apply(payment.id(), attempt.id(), update, TransitionSource.PROVIDER_RESPONSE);
        }
    }

    private static MandateUpdate toUpdate(ProviderMandateResult result) {
        MandateStatus status = switch (result.status()) {
            case PENDING -> MandateStatus.PENDING_AUTHORIZATION;
            case ACTIVE -> MandateStatus.ACTIVE;
            case PAUSED -> MandateStatus.PAUSED;
            case REVOKED -> MandateStatus.REVOKED;
            case EXPIRED -> MandateStatus.EXPIRED;
            case FAILED -> MandateStatus.FAILED;
            case NOT_FOUND -> null;
        };
        return new MandateUpdate(status, result.providerReference(), result.providerMandateReference(),
                result.providerCustomerReference(), result.nextAction(),
                result.failure() == null ? null : ProviderResults.toFailure(result.failure()));
    }
}
