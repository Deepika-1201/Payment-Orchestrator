package com.payments.gateway.payout;

import com.payments.gateway.ledger.LedgerModel.CashMovement;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.ledger.LedgerTransactionType;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.treasury.TreasuryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankReconciliationService {
    public record Transit(BankStatement.Kind kind, String referenceId) {
    }

    public record Result(String id, String statementId, long ledgerBalance, long bankBalance, long transitAmount,
                         long difference, boolean matched, List<String> issues, Instant createdAt) {
        public Result {
            issues = List.copyOf(issues);
        }
    }

    public record Imported(BankStatement statement, Result reconciliation) {
    }

    private final BankStatementRepository repository;
    private final LedgerService ledger;
    private final TreasuryService treasury;
    private final AuditLogger audit;
    private final Clock clock;
    private final EscrowProperties properties;

    public BankReconciliationService(BankStatementRepository repository, LedgerService ledger, TreasuryService treasury, AuditLogger audit, Clock clock,
                                     EscrowProperties properties) {
        this.repository = repository;
        this.ledger = ledger;
        this.treasury = treasury;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public Imported ingest(BankStatement input, String actor) {
        if (!properties.accountReference().equals(input.accountReference())) {
            throw GatewayException.validation("account_reference", "must identify the configured escrow bank account");
        }
        if (input.to().isAfter(clock.instant())) {
            throw GatewayException.validation("to", "cannot be in the future");
        }
        treasury.lock();
        var existing = repository.byReference(input.reference());
        BankStatement statement;
        if (existing.isPresent()) {
            statement = existing.get();
            if (!statement.accountReference().equals(input.accountReference()) || !statement.from().equals(input.from())
                    || !statement.to().equals(input.to()) || statement.openingBalance() != input.openingBalance()
                    || statement.closingBalance() != input.closingBalance() || !new HashSet<>(statement.entries()).equals(new HashSet<>(input.entries()))) {
                throw new GatewayException(ErrorCode.PAYOUT_INVALID_STATE, "Statement reference reused with different bank evidence");
            }
        } else {
            statement = new BankStatement(Ids.newId("bst"), input.reference(), input.accountReference(), input.from(), input.to(),
                    input.openingBalance(), input.closingBalance(), input.entries(), clock.instant());
            repository.insert(statement, actor);
            audit.record("ADMIN", actor, "bank_statement.imported", "bank_statement", statement.id(), Map.of("reference", statement.reference()));
        }
        return new Imported(statement, reconcile(statement, List.of()));
    }

    @Transactional
    public Result reconcile(String id, List<Transit> transit) {
        treasury.lock();
        return reconcile(repository.find(id).orElseThrow(() -> GatewayException.notFound("Bank statement", id)), transit);
    }

    public BankStatement get(String id) {
        return repository.find(id).orElseThrow(() -> GatewayException.notFound("Bank statement", id));
    }

    private Result reconcile(BankStatement statement, List<Transit> transit) {
        if (transit == null || transit.size() > 10_000) {
            throw GatewayException.validation("in_transit", "must contain at most 10000 known movements");
        }
        List<CashMovement> movements = ledger.cashMovements(TreasuryService.BOOK, clock.instant().plusNanos(1000));
        Map<Transit, Long> known = new HashMap<>();
        Map<Transit, Long> beforeStart = new HashMap<>();
        Map<Transit, Long> beforeEnd = new HashMap<>();
        Map<Transit, Long> window = new HashMap<>();
        List<String> issues = new ArrayList<>();
        long ledgerClosing = 0;
        long ledgerOpening = 0;
        for (CashMovement movement : movements) {
            if (movement.occurredAt().isBefore(statement.to())) {
                ledgerClosing = Math.addExact(ledgerClosing, movement.amount());
            }
            if (movement.occurredAt().isBefore(statement.from())) {
                ledgerOpening = Math.addExact(ledgerOpening, movement.amount());
            }
            Transit key = key(movement);
            if (key == null) {
                issues.add("unrecognized_ledger_cash_movement:" + movement.referenceId());
                continue;
            }
            known.merge(key, movement.amount(), Math::addExact);
            if (movement.occurredAt().isBefore(statement.to())) {
                beforeEnd.merge(key, movement.amount(), Math::addExact);
            }
            if (movement.occurredAt().isBefore(statement.from())) {
                beforeStart.merge(key, movement.amount(), Math::addExact);
            } else if (movement.occurredAt().isBefore(statement.to())) {
                window.merge(key, movement.amount(), Math::addExact);
            }
        }
        HashSet<Transit> cleared = new HashSet<>();
        long openingTransit = 0;
        long transitAmount = 0;
        for (var previous : repository.previousEntries(statement.accountReference(), statement.from())) {
            Transit key = bankKey(previous.statementId(), previous.entry());
            Long expected = known.get(key);
            if (expected != null && expected == previous.entry().amount() && cleared.add(key)) {
                openingTransit = Math.addExact(openingTransit, Math.subtractExact(beforeStart.getOrDefault(key, 0L), expected));
                transitAmount = Math.addExact(transitAmount, Math.subtractExact(beforeEnd.getOrDefault(key, 0L), expected));
            }
        }
        HashSet<Transit> seen = new HashSet<>();
        for (BankStatement.Entry entry : statement.entries()) {
            Transit key = bankKey(statement.id(), entry);
            Long expected = known.get(key);
            boolean first = seen.add(key);
            if (expected == null || expected != entry.amount() || !first || cleared.contains(key)) {
                issues.add("bank_entry_mismatch:" + entry.bankEntryId());
            } else {
                openingTransit = Math.addExact(openingTransit, beforeStart.getOrDefault(key, 0L));
                transitAmount = Math.addExact(transitAmount, Math.subtractExact(beforeEnd.getOrDefault(key, 0L), expected));
            }
        }
        HashSet<Transit> outstanding = new HashSet<>();
        for (Transit item : transit) {
            Long amount = beforeEnd.get(item);
            if (amount == null || seen.contains(item) || cleared.contains(item) || !outstanding.add(item)) {
                issues.add("invalid_in_transit:" + item.referenceId());
            } else {
                transitAmount = Math.addExact(transitAmount, amount);
                openingTransit = Math.addExact(openingTransit, beforeStart.getOrDefault(item, 0L));
            }
        }
        for (Transit item : window.keySet()) {
            if (!seen.contains(item) && !outstanding.contains(item) && !cleared.contains(item)) {
                issues.add("missing_bank_movement:" + item.referenceId());
            }
        }
        if (ledgerOpening != Math.addExact(statement.openingBalance(), openingTransit)) {
            issues.add("opening_balance_mismatch");
        }
        if (repository.previousClosing(statement.accountReference(), statement.from())
                .filter(closing -> closing != statement.openingBalance()).isPresent()) {
            issues.add("statement_continuity_mismatch");
        }
        long difference = Math.subtractExact(Math.addExact(statement.closingBalance(), transitAmount), ledgerClosing);
        if (difference != 0) {
            issues.add("closing_balance_mismatch");
        }
        Result result = new Result(Ids.newId("brc"), statement.id(), ledgerClosing, statement.closingBalance(), transitAmount, difference,
                issues.isEmpty(), issues, clock.instant());
        repository.result(result, transit);
        Map<String, Long> holds = new HashMap<>();
        for (CashMovement movement : movements) {
            if (movement.amount() > 0 && outstanding.contains(key(movement)) && !seen.contains(key(movement))
                    && !cleared.contains(key(movement))) {
                holds.merge(movement.merchantId(), movement.amount(), Math::addExact);
            }
        }
        treasury.bankResult(statement.to(), result.matched(), holds);
        if (!result.matched()) {
            treasury.raiseException(null, "ESCROW_DIFFERENCE", statement.id(), "Bank evidence differs from the book: " + String.join(",", issues));
        }
        return result;
    }

    private Transit bankKey(String statementId, BankStatement.Entry entry) {
        return entry.kind() == BankStatement.Kind.UNMATCHED
                ? repository.adjustmentForEntry(statementId, entry.bankEntryId())
                    .map(id -> new Transit(BankStatement.Kind.ADJUSTMENT, id)).orElse(new Transit(entry.kind(), entry.referenceId()))
                : new Transit(entry.kind(), entry.referenceId());
    }

    private static Transit key(CashMovement movement) {
        if ("ESCROW_RECEIPT".equals(movement.referenceType())) {
            String reference = movement.referenceId();
            int delimiter = reference.indexOf(':');
            return new Transit(BankStatement.Kind.PSP_RECEIPT, delimiter < 0 ? reference : reference.substring(0, delimiter));
        }
        if ("PAYOUT".equals(movement.referenceType())) {
            return new Transit(movement.type() == LedgerTransactionType.PAYOUT_SENT ? BankStatement.Kind.PAYOUT_DEBIT : BankStatement.Kind.PAYOUT_RETURN,
                    movement.referenceId());
        }
        if ("ESCROW_ADJUSTMENT".equals(movement.referenceType())) {
            return new Transit(BankStatement.Kind.ADJUSTMENT, movement.referenceId());
        }
        return null;
    }
}