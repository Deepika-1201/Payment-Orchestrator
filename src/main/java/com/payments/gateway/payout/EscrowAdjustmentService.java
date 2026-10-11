package com.payments.gateway.payout;

import com.payments.gateway.ledger.LedgerAccountType;
import com.payments.gateway.ledger.LedgerModel.Leg;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.ledger.LedgerTransactionType;
import com.payments.gateway.merchant.AggregationProperties;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.treasury.TreasuryService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EscrowAdjustmentService {
    public enum Kind {
        BANK_FEE, FEE_REVERSAL, PREFUNDING
    }

    public record Command(String reference, Kind kind, long amount, String statementId, String bankEntryId, String reason) {
    }

    public record Adjustment(String id, String reference, String ownerId, Kind kind, long amount, String statementId,
                             String bankEntryId, String reason, String status, String createdBy, Instant createdAt,
                             String decidedBy, Instant decidedAt) {
    }

    private final BankStatementRepository repository;
    private final TreasuryService treasury;
    private final LedgerService ledger;
    private final MerchantDirectory merchants;
    private final AggregationProperties aggregation;
    private final AuditLogger audit;
    private final Clock clock;

    public EscrowAdjustmentService(BankStatementRepository repository, TreasuryService treasury, LedgerService ledger,
                                    MerchantDirectory merchants, AggregationProperties aggregation, AuditLogger audit, Clock clock) {
        this.repository = repository;
        this.treasury = treasury;
        this.ledger = ledger;
        this.merchants = merchants;
        this.aggregation = aggregation;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Adjustment request(Command command, String actor) {
        if (command.reference() == null || command.reference().isBlank() || command.reference().length() > 128
                || command.kind() == null || command.amount() <= 0 || command.reason() == null || command.reason().isBlank()
                || command.reason().length() > 1000) {
            throw GatewayException.validation("adjustment", "requires a bounded reference/reason and a positive amount");
        }
        treasury.lock();
        var existing = repository.adjustment(command.reference(), true);
        if (existing.isPresent()) {
            Adjustment found = existing.get();
            if (found.kind() != command.kind() || found.amount() != command.amount() || !found.statementId().equals(command.statementId())
                    || !found.bankEntryId().equals(command.bankEntryId()) || !found.reason().equals(command.reason())) {
                throw invalid("Adjustment reference reused with different evidence");
            }
            return found;
        }
        BankStatement statement = repository.find(command.statementId()).orElseThrow(() -> GatewayException.notFound("Bank statement", command.statementId()));
        BankStatement.Entry entry = statement.entries().stream().filter(item -> item.bankEntryId().equals(command.bankEntryId())).findFirst()
                .orElseThrow(() -> GatewayException.notFound("Bank entry", command.bankEntryId()));
        long signed = command.kind() == Kind.BANK_FEE ? -command.amount() : command.amount();
        if (entry.kind() != BankStatement.Kind.UNMATCHED || entry.amount() != signed) {
            throw invalid("An escrow correction requires an unmatched bank entry with the same signed amount");
        }
        String owner = aggregation.platformMerchantId();
        merchants.require(owner);
        Adjustment adjustment = new Adjustment(Ids.newId("eadj"), command.reference(), owner, command.kind(), command.amount(), statement.id(),
                entry.bankEntryId(), command.reason(), "PENDING", actor, clock.instant(), null, null);
        repository.adjustment(adjustment);
        audit.record("ADMIN", actor, "escrow_adjustment.requested", "escrow_adjustment", adjustment.id(), Map.of("bank_entry_id", entry.bankEntryId()));
        return adjustment;
    }

    @Transactional
    public Adjustment decide(String id, boolean approve, String actor) {
        treasury.lock();
        Adjustment adjustment = get(id);
        String decision = approve ? "APPROVED" : "REJECTED";
        if (!"PENDING".equals(adjustment.status())) {
            if (decision.equals(adjustment.status())) {
                return adjustment;
            }
            throw invalid("A decided escrow correction cannot be changed");
        }
        if (approve) {
            if (actor.equals(adjustment.createdBy())) {
                throw new GatewayException(ErrorCode.FORBIDDEN, "A second operator must approve the escrow correction");
            }
            BankStatement statement = repository.find(adjustment.statementId()).orElseThrow();
            Instant occurred = statement.entries().stream().filter(entry -> entry.bankEntryId().equals(adjustment.bankEntryId())).findFirst().orElseThrow().bookedAt();
            Money amount = Money.of(adjustment.amount(), "INR");
            List<Leg> legs = switch (adjustment.kind()) {
                case BANK_FEE -> List.of(Leg.debit(LedgerAccountType.PLATFORM_FEE_INCOME, amount), Leg.credit(LedgerAccountType.ESCROW, amount));
                case FEE_REVERSAL -> List.of(Leg.debit(LedgerAccountType.ESCROW, amount), Leg.credit(LedgerAccountType.PLATFORM_FEE_INCOME, amount));
                case PREFUNDING -> List.of(Leg.debit(LedgerAccountType.ESCROW, amount), Leg.credit(LedgerAccountType.PLATFORM_FUNDING, amount));
            };
            ledger.post(new Posting(adjustment.ownerId(), TreasuryService.BOOK, LedgerTransactionType.ADJUSTMENT, "ESCROW_ADJUSTMENT", id,
                    adjustment.kind() + " verified against bank evidence", occurred, legs));
        }
        repository.decideAdjustment(id, decision, actor, clock.instant());
        audit.record("ADMIN", actor, approve ? "escrow_adjustment.approved" : "escrow_adjustment.rejected", "escrow_adjustment", id,
                Map.of("bank_entry_id", adjustment.bankEntryId()));
        return get(id);
    }

    public Adjustment get(String id) {
        return repository.adjustment(id, false).orElseThrow(() -> GatewayException.notFound("Escrow adjustment", id));
    }

    private static GatewayException invalid(String message) {
        return new GatewayException(ErrorCode.PAYOUT_INVALID_STATE, message);
    }
}