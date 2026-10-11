package com.payments.gateway.treasury;

import com.payments.gateway.ledger.LedgerAccountType;
import com.payments.gateway.ledger.LedgerModel.Leg;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.ledger.LedgerTransactionType;
import com.payments.gateway.merchant.Merchant;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.payment.application.ReviewService;
import com.payments.gateway.reconciliation.ReconciliationService;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.treasury.TreasuryModel.Batch;
import com.payments.gateway.treasury.TreasuryModel.Policy;
import com.payments.gateway.treasury.TreasuryModel.Source;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class SettlementService {
    public record PolicyCommand(int businessDays, long reserveTarget, boolean held, String bankRegistrationReference, String reason) {
    }

    private final TreasuryRepository repository;
    private final TreasuryService treasury;
    private final LedgerService ledger;
    private final MerchantDirectory merchants;
    private final ReviewService reviews;
    private final ReconciliationService reconciliation;
    private final AuditLogger audit;
    private final Clock clock;

    public SettlementService(TreasuryRepository repository, TreasuryService treasury, LedgerService ledger,
                             MerchantDirectory merchants, ReviewService reviews, ReconciliationService reconciliation,
                             AuditLogger audit, Clock clock) {
        this.repository = repository;
        this.treasury = treasury;
        this.ledger = ledger;
        this.merchants = merchants;
        this.reviews = reviews;
        this.reconciliation = reconciliation;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Policy policy(String merchantId, PolicyCommand command, String actor) {
        if (command.businessDays() < 1 || command.businessDays() > 30 || command.reserveTarget() < 0
                || command.reserveTarget() > 1_000_000_000_000L) {
            throw GatewayException.validation("policy", "requires 1-30 business days and a reserve target from 0 to 1000000000000");
        }
        text(command.bankRegistrationReference(), "bank_registration_reference", 200);
        text(command.reason(), "reason", 1000);
        repository.lock();
        merchants.lockForSettlement(merchantId);
        var approval = merchants.approvedDestination(merchantId);
        Policy policy = new Policy(Ids.newId("spol"), merchantId, approval.kycId(), command.businessDays(), command.reserveTarget(),
                command.held(), command.bankRegistrationReference(), command.reason(), actor, clock.instant());
        repository.policy(policy);
        audit.record("ADMIN", actor, "settlement_policy.published", "settlement_policy", policy.id(), Map.of("merchant_id", merchantId));
        return policy;
    }

    public Policy policy(String merchantId) {
        merchants.require(merchantId);
        return repository.policy(merchantId).orElseThrow(() -> invalid("A bank-registered settlement policy is required"));
    }

    @Transactional
    public Batch prepare(String merchantId, String reference, String actor) {
        text(reference, "reference", 128);
        repository.lock();
        var existing = repository.batchByReference(merchantId, reference);
        if (existing.isPresent()) {
            return existing.get();
        }
        Policy policy = releasable(merchantId);
        if (repository.hasDraft(merchantId)) {
            throw invalid("Review the existing draft before preparing another batch");
        }
        treasury.recoverReserve(merchantId, clock.instant());
        long reserve = repository.balance(merchantId).reserve();
        if (reserve > policy.reserveTarget()) {
            treasury.moveReserve(merchantId, reserve - policy.reserveTarget(), false, "policy:" + policy.id(), clock.instant());
            reserve = policy.reserveTarget();
        }
        long eligible = repository.unclaimedNet(merchantId, clock.instant());
        if (eligible <= 0) {
            throw invalid("No positive mature merchant balance is available");
        }
        if (reserve < policy.reserveTarget()) {
            treasury.moveReserve(merchantId, Math.min(policy.reserveTarget() - reserve, eligible), true,
                    "policy:" + policy.id(), clock.instant());
        }
        List<Source> sources = repository.unclaimed(merchantId, clock.instant());
        if (sources.size() > 10_000) {
            throw invalid("The batch exceeds 10000 sources; a reviewed higher-volume settlement workflow is required");
        }
        TreasuryAmounts.Totals totals = TreasuryAmounts.total(sources.stream().map(Source::line).toList());
        if (totals.net() < 0 || totals.net() > repository.balance(merchantId).merchantPayable()) {
            throw invalid("Mature sources are not backed by merchant payable");
        }
        checkCash(merchantId, totals.net());
        Batch batch = new Batch(Ids.newId("sbat"), merchantId, reference, policy.id(), policy.kycId(), "DRAFT", totals,
                totals.net(), actor, clock.instant(), null, null, null, sources);
        repository.batch(batch);
        transfer(batch, false);
        audit.record("ADMIN", actor, "settlement_batch.prepared", "settlement_batch", batch.id(),
                Map.of("merchant_id", merchantId, "amount", batch.amount(), "source_count", sources.size()));
        return batch;
    }

    @Transactional
    public Batch decide(String merchantId, String batchId, boolean approve, String reason, String actor) {
        text(reason, "reason", 1000);
        repository.lock();
        Batch batch = get(merchantId, batchId);
        String decision = approve ? "APPROVED" : "REJECTED";
        if (!"DRAFT".equals(batch.status())) {
            if (decision.equals(batch.status())) {
                return batch;
            }
            throw invalid("A decided settlement batch cannot be changed");
        }
        if (approve) {
            if (actor.equals(batch.createdBy())) {
                throw new GatewayException(ErrorCode.FORBIDDEN, "A second operator must approve the settlement batch");
            }
            Policy policy = releasable(merchantId);
            if (!policy.id().equals(batch.policyId()) || !policy.kycId().equals(batch.kycId())) {
                throw invalid("The policy or verified destination changed; reject and prepare a new batch");
            }
            if (repository.unclaimedNet(merchantId, clock.instant()) < 0) {
                throw invalid("New debits have reduced the batch's eligible backing; reject and prepare it again");
            }
            checkCash(merchantId, 0);
        }
        repository.decide(batch, decision, actor, reason, clock.instant());
        if (!approve) {
            transfer(batch, true);
        }
        audit.record("ADMIN", actor, approve ? "settlement_batch.approved" : "settlement_batch.rejected", "settlement_batch", batchId,
                Map.of("merchant_id", merchantId, "reason", reason));
        return get(merchantId, batchId);
    }

    public Batch get(String merchantId, String batchId) {
        return repository.batch(merchantId, batchId).orElseThrow(() -> GatewayException.notFound("Settlement batch", batchId));
    }

    public List<Batch> list(String merchantId) {
        merchants.require(merchantId);
        return repository.batches(merchantId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Policy releasable(String merchantId) {
        Merchant merchant = merchants.lockForSettlement(merchantId);
        var approval = merchants.approvedDestination(merchantId);
        Policy policy = policy(merchantId);
        if (merchant.status() != Merchant.Status.ACTIVE || policy.held()) {
            throw invalid("Merchant settlement is held");
        }
        if (!approval.kycId().equals(policy.kycId())) {
            throw invalid("The current verified destination has not been registered with the escrow bank");
        }
        if (!reviews.open(null, merchantId, 1).isEmpty() || !reviews.open(null, approval.ownerId(), 1).isEmpty()
                || reconciliation.hasOpenExceptions(merchantId) || reconciliation.hasOpenExceptions(approval.ownerId())) {
            throw invalid("Unresolved payment or reconciliation review holds this settlement");
        }
        return policy;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkCash(String merchantId, long additional) {
        var global = repository.balance(null);
        var merchant = repository.balance(merchantId);
        if (global.difference() != 0 || merchant.difference() != 0
                || Math.subtractExact(global.escrow(), repository.protectedCash(null)) < additional
                || Math.subtractExact(merchant.escrow(), repository.protectedCash(merchantId)) < additional) {
            throw invalid("Insufficient uncommitted escrow cash or an unbalanced authoritative book");
        }
    }

    private void transfer(Batch batch, boolean release) {
        if (batch.amount() == 0) {
            return;
        }
        Money amount = Money.of(batch.amount(), "INR");
        ledger.post(new Posting(batch.merchantId(), TreasuryService.BOOK,
                release ? LedgerTransactionType.SETTLEMENT_RELEASED : LedgerTransactionType.SETTLEMENT_RESERVED,
                "SETTLEMENT_BATCH", batch.id(), release ? "Rejected draft released" : "Draft settlement reserved", clock.instant(),
                release ? List.of(Leg.debit(LedgerAccountType.SETTLEMENT_PAYABLE, amount), Leg.credit(LedgerAccountType.MERCHANT_PAYABLE, amount))
                        : List.of(Leg.debit(LedgerAccountType.MERCHANT_PAYABLE, amount), Leg.credit(LedgerAccountType.SETTLEMENT_PAYABLE, amount))));
    }

    private static void text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw GatewayException.validation(field, "must contain 1-" + maximum + " characters");
        }
    }

    static GatewayException invalid(String message) {
        return new GatewayException(ErrorCode.SETTLEMENT_INVALID_STATE, message);
    }
}