package com.payments.gateway.payout;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.payout.PayoutState.Status;
import com.payments.gateway.payout.PayoutState.Transition;
import com.payments.gateway.payout.spi.PayoutProvider;
import com.payments.gateway.payout.spi.PayoutProvider.Destination;
import com.payments.gateway.payout.spi.PayoutProvider.Instruction;
import com.payments.gateway.payout.spi.PayoutProvider.Outcome;
import com.payments.gateway.payout.spi.PayoutProvider.Result;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.events.PayoutFundsMovement;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.web.WireEnums;
import com.payments.gateway.treasury.SettlementService;
import com.payments.gateway.treasury.TreasuryService;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PayoutService {
    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    public record View(String id, String merchantId, String batchId, String provider, long amount, String currency, String status,
                       String bankAccountLastFour, String ifsc, String providerReference, String bankReference,
                       String reason, Instant dispatchedAt, int checks, Instant createdAt, Instant updatedAt) {
    }

    private record Work(PayoutRecord payout, Instruction instruction, boolean create) {
    }

    private final PayoutRepository repository;
    private final PayoutProviders providers;
    private final PayoutProperties properties;
    private final MerchantDirectory merchants;
    private final SettlementService settlements;
    private final TreasuryService treasury;
    private final SecretCipher cipher;
    private final JsonCodec json;
    private final ApplicationEventPublisher events;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BankStatementRepository statements;
    private final EscrowProperties escrow;

    public PayoutService(PayoutRepository repository, PayoutProviders providers, PayoutProperties properties, MerchantDirectory merchants,
                         SettlementService settlements, TreasuryService treasury, SecretCipher cipher, JsonCodec json,
                         ApplicationEventPublisher events, AuditLogger audit, TransactionTemplate tx, Clock clock,
                         BankStatementRepository statements, EscrowProperties escrow) {
        this.repository = repository;
        this.providers = providers;
        this.properties = properties;
        this.merchants = merchants;
        this.settlements = settlements;
        this.treasury = treasury;
        this.cipher = cipher;
        this.json = json;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.statements = statements;
        this.escrow = escrow;
    }

    public View queue(String merchantId, String batchId, String actor) {
        return tx.execute(status -> {
            treasury.lock();
            var existing = repository.byBatch(merchantId, batchId);
            if (existing.isPresent()) {
                return view(existing.get());
            }
            if (!properties.enabled()) {
                throw invalid("New payouts are disabled");
            }
            var batch = settlements.get(merchantId, batchId);
            if (!"APPROVED".equals(batch.status()) || batch.amount() <= 0) {
                throw invalid("Only an approved positive settlement batch needs a payout");
            }
            var bank = merchants.bankDestination(merchantId, batch.kycId());
            PayoutProvider provider = providers.require(properties.provider());
            String id = Ids.newId("pout");
            Instruction instruction = new Instruction(id, merchantId, provider.sourceAccount(), Money.of(batch.amount(), "INR"),
                    new Destination(bank.beneficiary(), bank.accountNumber(), bank.ifsc()));
            String encoded = json.write(instruction);
            Instant now = clock.instant();
            PayoutRecord payout = new PayoutRecord(id, merchantId, batchId, batch.kycId(), provider.code(), batch.amount(),
                    cipher.encrypt(encoded, PayoutRepository.context(id)), Hashing.sha256(encoded), Status.QUEUED,
                    null, null, null, null, 0, now, now, now);
            repository.insert(payout);
            audit.record("ADMIN", actor, "payout.queued", "payout", id, Map.of("merchant_id", merchantId, "batch_id", batchId));
            return view(payout);
        });
    }

    public View get(String merchantId, String payoutId) {
        return view(require(merchantId, payoutId, false));
    }

    public List<View> list(String merchantId) {
        merchants.require(merchantId);
        return repository.list(merchantId).stream().map(this::view).toList();
    }

    public View cancel(String merchantId, String payoutId, String actor) {
        return tx.execute(status -> {
            treasury.lock();
            PayoutRecord payout = require(merchantId, payoutId, true);
            Transition transition;
            try {
                transition = PayoutState.cancel(payout.status(), payout.dispatchedAt() != null);
            } catch (IllegalStateException exception) {
                throw invalid(exception.getMessage());
            }
            if (transition.effect() != PayoutState.Effect.NONE) {
                publish(payout, transition.effect(), clock.instant());
                repository.update(payout, transition.status(), payout.providerReference(), payout.bankReference(), "cancelled_before_dispatch", null, clock.instant());
                audit.record("ADMIN", actor, "payout.cancelled", "payout", payoutId, Map.of("merchant_id", merchantId));
            }
            return view(repository.find(payoutId, false).orElseThrow());
        });
    }

    public View check(String merchantId, String payoutId) {
        PayoutRecord payout = require(merchantId, payoutId, false);
        if (payout.dispatchedAt() != null && payout.status() != Status.RETURNED && payout.status() != Status.REJECTED) {
            query(new Work(payout, instruction(payout), false));
        }
        return get(merchantId, payoutId);
    }

    public int run() {
        Instant now = clock.instant();
        List<String> due = repository.claim(now, now.plus(Duration.ofMinutes(2)), properties.batchSize());
        for (String id : due) {
            try {
                advance(id);
            } catch (RuntimeException exception) {
                log.error("Payout {} could not advance: {}", id, exception.getClass().getSimpleName());
            }
        }
        return due.size();
    }

    public void advance(String id) {
        Work work = tx.execute(status -> {
            treasury.lock();
            PayoutRecord payout = repository.find(id, true).orElseThrow(() -> GatewayException.notFound("Payout", id));
            if (payout.status() == Status.RETURNED || payout.status() == Status.REJECTED || payout.status() == Status.CANCELLED) {
                return null;
            }
            Instruction instruction = instruction(payout);
            if (payout.dispatchedAt() != null) {
                return new Work(payout, instruction, false);
            }
            if (!properties.enabled()) {
                repository.update(payout, payout.status(), null, null, "dispatch_disabled", clock.instant().plus(properties.checkInterval()), clock.instant());
                return null;
            }
            try {
                if (escrow.requireCurrentStatement() && !statements.hasCurrentMatchedStatement(escrow.accountReference(),
                        clock.instant().minus(escrow.statementAgeLimit()))) {
                    throw invalid("A current matched escrow bank statement is required before dispatch");
                }
                settlements.checkDispatch(payout.merchantId(), payout.batchId());
                providers.require(payout.providerCode()).validate(instruction);
            } catch (GatewayException | IllegalArgumentException exception) {
                repository.update(payout, payout.status(), null, null, "dispatch_held", clock.instant().plus(properties.checkInterval()), clock.instant());
                return null;
            }
            boolean send = repository.dispatch(id, clock.instant(), clock.instant().plus(properties.checkInterval()));
            if (!send) {
                return null;
            }
            return new Work(repository.find(id, false).orElseThrow(), instruction, true);
        });
        if (work != null) {
            query(work);
        }
    }

    private void query(Work work) {
        Result result;
        try {
            PayoutProvider provider = providers.require(work.payout().providerCode());
            result = work.create() ? provider.create(work.instruction())
                    : provider.fetch(work.instruction(), work.payout().providerReference());
        } catch (RuntimeException exception) {
            result = Result.unknown(exception.getClass().getSimpleName());
        }
        if (result == null || result.outcome() == null) {
            result = Result.unknown("malformed_provider_result");
        }
        apply(work.payout().providerCode(), work.payout().id(), result);
    }

    public boolean apply(String providerCode, String payoutId, Result result) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            treasury.lock();
            var found = repository.find(payoutId, true);
            if (found.isEmpty() || !found.get().providerCode().equals(providerCode) || found.get().dispatchedAt() == null) {
                return false;
            }
            PayoutRecord payout = found.get();
            Instruction instruction = instruction(payout);
            Instant now = clock.instant();
            boolean certain = result.outcome() != Outcome.UNKNOWN && result.outcome() != Outcome.NOT_FOUND;
            if (certain && !matches(payout, instruction, result)) {
                treasury.raiseException(payout.merchantId(), "PAYOUT_CONFLICT", payout.id(), "Payout evidence differs from the immutable instruction");
                Status next = payout.status() == Status.PENDING ? Status.REVIEW : payout.status();
                repository.update(payout, next, payout.providerReference(), payout.bankReference(), "evidence_mismatch", nextCheck(payout, next, now), now);
                return false;
            }
            Transition transition = PayoutState.apply(payout.status(), true, result.outcome());
            if (transition.conflict()) {
                treasury.raiseException(payout.merchantId(), "PAYOUT_CONFLICT", payout.id(), "Contradictory terminal payout evidence");
            }
            Status next = transition.status();
            if ((next == Status.PENDING || next == Status.REVIEW) && !now.isBefore(payout.dispatchedAt().plus(properties.reviewAfter()))) {
                next = Status.REVIEW;
                treasury.raiseException(payout.merchantId(), "PAYOUT_UNRESOLVED", payout.id(), "Payout remains unresolved beyond its review deadline; do not resend");
            }
            if ((next == Status.PENDING || next == Status.REVIEW) && result.outcome() == Outcome.FAILURE_UNCONFIRMED) {
                treasury.raiseException(payout.merchantId(), "PAYOUT_UNRESOLVED", payout.id(), "Provider reported failure without confirmed cash return");
            }
            publish(payout, transition.effect(), now);
            if (transition.effect() == PayoutState.Effect.RETURN || transition.effect() == PayoutState.Effect.PAID_AND_RETURN) {
                treasury.raiseException(payout.merchantId(), "PAYOUT_RETURNED", payout.id(), "Payout principal returned; review the destination before a new batch");
            }
            String reference = payout.providerReference() == null && certain ? result.providerReference() : payout.providerReference();
            String bank = payout.bankReference() == null && certain ? result.bankReference() : payout.bankReference();
            repository.update(payout, next, reference, bank, result.reason(), nextCheck(payout, next, now), now);
            if (transition.effect() != PayoutState.Effect.NONE) {
                audit.record("SYSTEM", "payout-worker", "payout." + WireEnums.wire(next), "payout", payoutId,
                        Map.of("merchant_id", payout.merchantId(), "amount", payout.amount()));
            }
            return true;
        }));
    }

    private Instant nextCheck(PayoutRecord payout, Status next, Instant now) {
        return switch (next) {
            case PENDING, REVIEW -> now.plus(properties.checkInterval().multipliedBy(1L << Math.min(payout.checks(), 6)));
            case SUCCEEDED -> now.isBefore(payout.dispatchedAt().plus(Duration.ofDays(30))) ? now.plus(Duration.ofDays(1)) : null;
            default -> null;
        };
    }

    private void publish(PayoutRecord payout, PayoutState.Effect effect, Instant now) {
        if (effect == PayoutState.Effect.PAID || effect == PayoutState.Effect.PAID_AND_RETURN) {
            events.publishEvent(new PayoutFundsMovement(PayoutFundsMovement.Type.PAID, payout.id(), payout.merchantId(), Money.of(payout.amount(), "INR"), now));
        }
        if (effect == PayoutState.Effect.RELEASE) {
            events.publishEvent(new PayoutFundsMovement(PayoutFundsMovement.Type.RELEASED, payout.id(), payout.merchantId(), Money.of(payout.amount(), "INR"), now));
        }
        if (effect == PayoutState.Effect.RETURN || effect == PayoutState.Effect.PAID_AND_RETURN) {
            events.publishEvent(new PayoutFundsMovement(PayoutFundsMovement.Type.RETURNED, payout.id(), payout.merchantId(), Money.of(payout.amount(), "INR"), now));
        }
    }

    private static boolean matches(PayoutRecord payout, Instruction instruction, Result result) {
        boolean terminal = result.outcome() == Outcome.SUCCEEDED || result.outcome() == Outcome.RETURNED || result.outcome() == Outcome.REJECTED;
        return payout.id().equals(result.referenceId()) && instruction.amount().equals(result.amount())
                && result.providerReference() != null && !result.providerReference().isBlank()
                && (payout.providerReference() == null || payout.providerReference().equals(result.providerReference()))
                && (!terminal || (result.destination() != null
                    && instruction.destination().accountNumber().equals(result.destination().accountNumber())
                    && instruction.destination().ifsc().equals(result.destination().ifsc())));
    }

    public Instruction instruction(PayoutRecord payout) {
        String encoded = cipher.decrypt(payout.instructionEncrypted(), PayoutRepository.context(payout.id()));
        if (!MessageDigest.isEqual(Hashing.sha256(encoded), payout.instructionHash())) {
            throw new IllegalStateException("Payout instruction fingerprint mismatch");
        }
        return json.read(encoded, Instruction.class);
    }

    private PayoutRecord require(String merchantId, String id, boolean lock) {
        return repository.find(id, lock).filter(payout -> payout.merchantId().equals(merchantId))
                .orElseThrow(() -> GatewayException.notFound("Payout", id));
    }

    private View view(PayoutRecord payout) {
        Destination destination = instruction(payout).destination();
        String account = destination.accountNumber();
        return new View(payout.id(), payout.merchantId(), payout.batchId(), payout.providerCode(), payout.amount(), "INR", WireEnums.wire(payout.status()),
                account.substring(account.length() - 4), destination.ifsc(), payout.providerReference(), payout.bankReference(), payout.reason(),
                payout.dispatchedAt(), payout.checks(), payout.createdAt(), payout.updatedAt());
    }

    private static GatewayException invalid(String message) {
        return new GatewayException(ErrorCode.PAYOUT_INVALID_STATE, message);
    }
}