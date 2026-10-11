package com.payments.gateway.payout;

import com.payments.gateway.merchant.AggregationProperties;
import com.payments.gateway.treasury.SettlementCalendar;
import com.payments.gateway.treasury.TreasuryService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class EscrowMonitor {
    private final TreasuryService treasury;
    private final PayoutRepository payouts;
    private final BankStatementRepository statements;
    private final BankReconciliationService bank;
    private final AggregationProperties aggregation;
    private final PayoutProperties properties;
    private final EscrowProperties escrow;
    private final Clock clock;
    private final AtomicLong discrepancy = new AtomicLong();
    private final AtomicLong missingStatement = new AtomicLong();
    private final AtomicLong negativeMerchants = new AtomicLong();
    private final AtomicLong unresolvedPayouts = new AtomicLong();
    private final AtomicLong returnedPayouts = new AtomicLong();

    public EscrowMonitor(TreasuryService treasury, PayoutRepository payouts, BankStatementRepository statements,
                         BankReconciliationService bank, AggregationProperties aggregation, PayoutProperties properties,
                         EscrowProperties escrow, Clock clock, MeterRegistry meters) {
        this.treasury = treasury;
        this.payouts = payouts;
        this.statements = statements;
        this.bank = bank;
        this.aggregation = aggregation;
        this.properties = properties;
        this.escrow = escrow;
        this.clock = clock;
        Gauge.builder("pg.escrow.discrepancy", discrepancy, AtomicLong::get).register(meters);
        Gauge.builder("pg.escrow.statement.missing", missingStatement, AtomicLong::get).register(meters);
        Gauge.builder("pg.escrow.negative.merchants", negativeMerchants, AtomicLong::get).register(meters);
        Gauge.builder("pg.payouts.unresolved", unresolvedPayouts, AtomicLong::get).register(meters);
        Gauge.builder("pg.payouts.returned.open", returnedPayouts, AtomicLong::get).register(meters);
    }

    public void run() {
        var now = clock.instant();
        var balance = treasury.balance(null);
        var negative = treasury.negativeMerchants();
        negativeMerchants.set(negative.size());
        negative.forEach((merchant, amount) -> treasury.raiseException(merchant, "NEGATIVE_MERCHANT_BALANCE", merchant,
                "Merchant payable is negative: " + amount + " INR minor units; recover from its reserve or later receipts"));
        var cutoff = now.minus(properties.reviewAfter());
        unresolvedPayouts.set(payouts.countOverdue(cutoff));
        payouts.overdue(cutoff).forEach(payout -> treasury.raiseException(payout.merchantId(), "PAYOUT_UNRESOLVED", payout.id(),
                payout.dispatchedAt() == null ? "Payout remains held before dispatch" : "Payout outcome remains unresolved; never resend"));
        boolean missing = aggregation.enabled() && statements.latestEnd().map(end -> end.isBefore(now.minus(escrow.statementAgeLimit()))).orElse(true);
        missingStatement.set(missing ? 1 : 0);
        if (missing) {
            treasury.raiseException(null, "ESCROW_STATEMENT_MISSING", escrow.accountReference() + ":" + now.atZone(SettlementCalendar.ZONE).toLocalDate(),
                    "The configured escrow bank statement is missing or older than its required delivery window");
        }
        if (balance.difference() != 0) {
            treasury.raiseException(null, "ESCROW_INVARIANT", "authoritative-book", "Escrow book invariant differs by " + balance.difference());
        }
        discrepancy.set(balance.difference() != 0 || treasury.bankMismatch() ? 1 : 0);
        returnedPayouts.set(treasury.countExceptions("PAYOUT_RETURNED"));
    }

    public void reconcileLatest() {
        statements.latestId().ifPresent(id -> bank.reconcile(id, statements.previousTransit(id)));
        run();
    }
}