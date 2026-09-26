package com.payments.gateway.reconciliation;

import com.payments.gateway.ledger.LedgerAccountType;
import com.payments.gateway.ledger.LedgerModel.Leg;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.ledger.LedgerTransactionType;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.payment.application.PaymentReconciliationService;
import com.payments.gateway.payment.application.PaymentReconciliationService.InternalItem;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.provider.spi.ProviderException;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.reconciliation.ReconciliationRepository.ExceptionRow;
import com.payments.gateway.reconciliation.ReconciliationRepository.RunRow;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Three-way reconciliation of a merchant PSP account for a time window (FR-RC1..4):
 * PSP settlement report ↔ internal attempts/refunds ↔ shadow ledger. Safe discrepancies are auto-healed through
 * the payment domain (source RECONCILIATION); everything else becomes a deduplicated exception.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Duration MAX_WINDOW = Duration.ofDays(31);

    public record RunSummary(String id, String merchantId, String provider, Instant windowStart, Instant windowEnd,
                             String status, int linesTotal, int linesMatched, int linesAutoHealed, int exceptionsOpened,
                             long grossAmount, long refundAmount, long feeAmount, long settledAmount,
                             long chargebackAmount, String error,
                             Instant startedAt, Instant completedAt, List<ExceptionView> exceptions) {
    }

    public record ExceptionView(String id, String runId, String merchantId, String provider, String type,
                                String reference, String entityId, Long expectedAmount, Long actualAmount,
                                String details, String status, String resolution, Instant createdAt, Instant resolvedAt,
                                Instant dueAt, boolean overdue, String assignee, Instant assignedAt) {
    }

    /** One business day's reconciliation across merchant PSP accounts (ADR-017). */
    public record DailyReport(LocalDate date, String zone, Instant windowStart, Instant windowEnd,
                              List<AccountResult> accounts, ExceptionSummary exceptions, Backlog backlog) {
    }

    /** {@code status} is the run's status, or {@code missing} when the account was due but has no run. */
    public record AccountResult(String merchantId, String provider, String status, String runId, Integer linesTotal,
                                Integer linesMatched, Integer linesAutoHealed, Integer exceptionsOpened,
                                Long grossAmount, Long refundAmount, Long feeAmount, Long settledAmount,
                                Long chargebackAmount, String error) {
    }

    /** Exceptions opened by the day's runs and where they stand now. */
    public record ExceptionSummary(int opened, int resolved, int open, int overdue, Map<String, Integer> openedByType) {
    }

    /** All open exceptions, whatever day they came from. */
    public record Backlog(long open, long overdue) {
    }

    private enum Result {
        MATCHED,
        AUTO_HEALED,
        EXCEPTION
    }

    private record LineOutcome(Result result, InternalItem item) {
    }

    /** Mutable state of one run. */
    private final class RunContext {
        final String runId;
        final String merchantId;
        final String provider;
        final Instant now;
        int total;
        int matched;
        int healed;
        int exceptions;
        long gross;
        long refunds;
        long fees;
        long settled;
        long chargebacks;
        final Map<String, Long> internalNetBySettlement = new HashMap<>();

        RunContext(String runId, String merchantId, String provider, Instant now) {
            this.runId = runId;
            this.merchantId = merchantId;
            this.provider = provider;
            this.now = now;
        }

        void open(String type, String reference, String entityId, Long expected, Long actual, String details) {
            if (repository.openException(Ids.newId("rex"), runId, merchantId, provider, type, reference, entityId,
                    expected, actual, details, now, now.plus(properties.exceptionSla()))) {
                exceptions++;
                meters.counter("pg.reconciliation.exceptions", "provider", provider, "type", type.toLowerCase(Locale.ROOT)).increment();
            }
        }
    }

    private final ReconciliationRepository repository;
    private final PaymentReconciliationService payments;
    private final LedgerService ledger;
    private final ProviderClient providerClient;
    private final ProviderRegistry providers;
    private final MerchantDirectory merchants;
    private final AuditLogger audit;
    private final ReconciliationProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    public ReconciliationService(ReconciliationRepository repository, PaymentReconciliationService payments,
                                 LedgerService ledger, ProviderClient providerClient, ProviderRegistry providers,
                                 MerchantDirectory merchants, AuditLogger audit, ReconciliationProperties properties,
                                 Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.payments = payments;
        this.ledger = ledger;
        this.providerClient = providerClient;
        this.providers = providers;
        this.merchants = merchants;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        Gauge.builder("pg.reconciliation.exceptions.open", repository, r -> r.countOpenExceptions(null))
                .description("Open reconciliation exceptions").register(meters);
        Gauge.builder("pg.reconciliation.exceptions.overdue", repository, r -> r.countOverdueExceptions(null, clock.instant()))
                .description("Open reconciliation exceptions past their SLA").register(meters);
    }

    public RunSummary run(String merchantId, String providerCode, Instant from, Instant to) {
        merchants.require(merchantId);
        PaymentProvider provider = providers.find(providerCode)
                .orElseThrow(() -> GatewayException.validation("provider", "is unknown: " + providerCode));
        if (!provider.capabilities().settlementReports()) {
            throw GatewayException.validation("provider", providerCode + " does not provide settlement reports");
        }
        if (!merchants.hasProviderAccount(merchantId, providerCode)) {
            throw GatewayException.validation("provider", "merchant " + merchantId + " has no " + providerCode + " account");
        }
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw GatewayException.validation("window", "requires from < to and a window of at most 31 days");
        }
        RunContext run = new RunContext(Ids.newId("recon"), merchantId, providerCode, clock.instant());
        repository.insertRun(run.runId, merchantId, providerCode, from, to, run.now);
        SettlementReport report;
        try {
            report = providerClient.fetchSettlementReport(providerCode, new SettlementReportQuery(merchantId, from, to));
        } catch (ProviderException e) {
            repository.failRun(run.runId, e.getMessage(), clock.instant());
            throw new GatewayException(ErrorCode.NO_PROVIDER_AVAILABLE, "Could not fetch the settlement report: " + e.getMessage(),
                    List.of(), 60);
        }
        try {
            reconcileLines(run, report);
            postSettlements(run, report);
            flagMissingAtProvider(run, from, to);
            repository.completeRun(run.runId, new ReconciliationRepository.Totals(run.total, run.matched, run.healed,
                    run.exceptions, run.gross, run.refunds, run.fees, run.settled, run.chargebacks), clock.instant());
        } catch (RuntimeException e) {
            log.error("Reconciliation run {} failed", run.runId, e);
            repository.failRun(run.runId, e.getClass().getSimpleName() + ": " + e.getMessage(), clock.instant());
            throw e;
        }
        log.info("Reconciliation run {} for {}/{}: lines={} matched={} healed={} exceptions={}", run.runId, merchantId,
                providerCode, run.total, run.matched, run.healed, run.exceptions);
        return get(run.runId);
    }

    /**
     * Daily T+1 job: reconciles the previous local day for every merchant PSP account, including suspended merchants
     * and accounts disabled within the last week, whose earlier payments still settle.
     */
    public int runForPreviousDay() {
        Window window = window(LocalDate.ofInstant(clock.instant(), properties.zone()).minusDays(1));
        int runs = 0;
        for (MerchantDirectory.ProviderAccount account : accountsToReconcile(window, null)) {
            try {
                run(account.merchantId(), account.providerCode(), window.from(), window.to());
                runs++;
            } catch (RuntimeException e) {
                log.warn("Daily reconciliation failed for {}/{}", account.merchantId(), account.providerCode(), e);
            }
        }
        return runs;
    }

    /**
     * The reconciliation of one local business day: each due account's latest run for that day (or {@code missing}),
     * what happened to the exceptions those runs opened, and the overall open backlog.
     */
    public DailyReport dailyReport(LocalDate date, String merchantId) {
        Instant now = clock.instant();
        if (date.isAfter(LocalDate.ofInstant(now, properties.zone()))) {
            throw GatewayException.validation("date", "must not be in the future");
        }
        Window window = window(date);
        Map<String, AccountResult> accounts = new TreeMap<>();
        for (MerchantDirectory.ProviderAccount account : accountsToReconcile(window, merchantId)) {
            accounts.put(account.merchantId() + "/" + account.providerCode(), new AccountResult(account.merchantId(),
                    account.providerCode(), "missing", null, null, null, null, null, null, null, null, null, null, null));
        }
        for (RunRow run : repository.latestRunsForWindow(window.from(), window.to(), merchantId)) {
            accounts.put(run.merchantId() + "/" + run.providerCode(), new AccountResult(run.merchantId(),
                    run.providerCode(), run.status().toLowerCase(Locale.ROOT), run.id(), run.linesTotal(),
                    run.linesMatched(), run.linesAutoHealed(), run.exceptionsOpened(), run.grossAmount(),
                    run.refundAmount(), run.feeAmount(), run.settledAmount(), run.chargebackAmount(), run.error()));
        }
        int opened = 0;
        int resolved = 0;
        int open = 0;
        int overdue = 0;
        Map<String, Integer> byType = new TreeMap<>();
        for (ReconciliationRepository.ExceptionTally tally : repository.tallyExceptionsForWindow(window.from(), window.to(),
                merchantId, now)) {
            opened += tally.count();
            byType.merge(tally.type().toLowerCase(Locale.ROOT), tally.count(), Integer::sum);
            if ("OPEN".equals(tally.status())) {
                open += tally.count();
                overdue += tally.overdue() ? tally.count() : 0;
            } else {
                resolved += tally.count();
            }
        }
        return new DailyReport(date, properties.zone().getId(), window.from(), window.to(), List.copyOf(accounts.values()),
                new ExceptionSummary(opened, resolved, open, overdue, byType),
                new Backlog(repository.countOpenExceptions(merchantId), repository.countOverdueExceptions(merchantId, now)));
    }

    private record Window(Instant from, Instant to) {
    }

    private Window window(LocalDate day) {
        return new Window(day.atStartOfDay(properties.zone()).toInstant(),
                day.plusDays(1).atStartOfDay(properties.zone()).toInstant());
    }

    /** Accounts the daily job reconciles for this window: settlement-report capable, active or recently disabled. */
    private List<MerchantDirectory.ProviderAccount> accountsToReconcile(Window window, String merchantId) {
        return merchants.providerAccountsToReconcile(window.from().minus(Duration.ofDays(7))).stream()
                .filter(account -> merchantId == null || merchantId.equals(account.merchantId()))
                .filter(account -> providers.find(account.providerCode())
                        .map(p -> p.capabilities().settlementReports()).orElse(false))
                .toList();
    }

    public RunSummary get(String runId) {
        RunRow row = repository.findRun(runId).orElseThrow(() -> GatewayException.notFound("Reconciliation run", runId));
        Instant now = clock.instant();
        return new RunSummary(row.id(), row.merchantId(), row.providerCode(), row.windowStart(), row.windowEnd(),
                row.status().toLowerCase(Locale.ROOT), row.linesTotal(), row.linesMatched(), row.linesAutoHealed(),
                row.exceptionsOpened(), row.grossAmount(), row.refundAmount(), row.feeAmount(), row.settledAmount(),
                row.chargebackAmount(),
                row.error(), row.startedAt(), row.completedAt(),
                repository.exceptionsForRun(runId).stream().map(e -> toView(e, now)).toList());
    }

    public List<ExceptionView> exceptions(String status, String merchantId, String assignee, boolean overdueOnly) {
        String normalized = status == null ? null : status.toUpperCase(Locale.ROOT);
        if (normalized != null && !normalized.equals("OPEN") && !normalized.equals("RESOLVED")) {
            throw GatewayException.validation("status", "must be open or resolved");
        }
        Instant now = clock.instant();
        var filter = new ReconciliationRepository.ExceptionFilter(normalized, merchantId, assignee, overdueOnly ? now : null);
        return repository.exceptions(filter, 500).stream().map(e -> toView(e, now)).toList();
    }

    public ExceptionView assign(String exceptionId, String assignee, String actor) {
        ExceptionRow row = repository.findException(exceptionId)
                .orElseThrow(() -> GatewayException.notFound("Reconciliation exception", exceptionId));
        if (!repository.assignException(exceptionId, assignee, clock.instant())) {
            throw GatewayException.invalidState("Exception " + exceptionId + " is already " + row.status().toLowerCase(Locale.ROOT));
        }
        Map<String, Object> details = new HashMap<>();
        details.put("assignee", assignee);
        details.put("previous_assignee", row.assignee());
        audit.record("ADMIN", actor, "reconciliation_exception.assigned", "reconciliation_exception", exceptionId, details);
        return toView(repository.findException(exceptionId).orElseThrow(), clock.instant());
    }

    public ExceptionView resolve(String exceptionId, String resolution, String actor) {
        ExceptionRow row = repository.findException(exceptionId)
                .orElseThrow(() -> GatewayException.notFound("Reconciliation exception", exceptionId));
        if (!repository.resolveException(exceptionId, resolution, clock.instant())) {
            throw GatewayException.invalidState("Exception " + exceptionId + " is already " + row.status().toLowerCase(Locale.ROOT));
        }
        audit.record("ADMIN", actor, "reconciliation_exception.resolved", "reconciliation_exception", exceptionId,
                Map.of("type", row.type(), "reference", row.reference(), "resolution", resolution));
        return toView(repository.findException(exceptionId).orElseThrow(), clock.instant());
    }

    // ------------------------------------------------------------------ reconciliation steps

    private void reconcileLines(RunContext run, SettlementReport report) {
        Set<String> seen = new HashSet<>();
        for (SettlementReport.Line line : report.lines()) {
            run.total++;
            long fee = line.fee() == null ? 0 : line.fee().amount();
            switch (line.type()) {
                case PAYMENT -> {
                    run.gross += line.amount().amount();
                    run.fees += fee;
                }
                case REFUND -> run.refunds += line.amount().amount();
                case CHARGEBACK -> run.chargebacks += line.amount().amount();
                case CHARGEBACK_REVERSAL -> run.chargebacks -= line.amount().amount();
            }
            String reference = line.providerReference() == null ? line.lineId() : line.providerReference();
            if (!seen.add(line.type() + "|" + reference)) {
                run.open("DUPLICATE", reference, null, null, line.amount().amount(), "Reference appears more than once in the report");
                repository.insertLine(run.runId, run.provider, line, Result.EXCEPTION.name(), null);
                continue;
            }
            LineOutcome outcome = switch (line.type()) {
                case PAYMENT -> reconcilePayment(run, line, reference);
                case REFUND -> reconcileRefund(run, line, reference);
                case CHARGEBACK -> reconcileChargeback(run, line, reference);
                case CHARGEBACK_REVERSAL -> reconcileChargebackReversal(run, line, reference);
            };
            repository.insertLine(run.runId, run.provider, line, outcome.result().name(),
                    outcome.item() == null ? null : outcome.item().entityId());
            if (outcome.result() == Result.EXCEPTION) {
                continue;
            }
            if (outcome.result() == Result.MATCHED) {
                run.matched++;
            } else {
                run.healed++;
            }
            repository.autoResolveMissingAtProvider(run.merchantId, run.provider, outcome.item().entityId(), run.runId, run.now);
            long signedInternal = switch (line.type()) {
                case PAYMENT -> outcome.item().amount().amount() - fee;
                case REFUND, CHARGEBACK -> -outcome.item().amount().amount();
                case CHARGEBACK_REVERSAL -> outcome.item().amount().amount();
            };
            if (line.settlementId() != null) {
                run.internalNetBySettlement.merge(line.settlementId(), signedInternal, Long::sum);
            }
            if (line.type() == SettlementReport.LineType.PAYMENT && fee > 0) {
                postFee(run, line);
            }
        }
    }

    private LineOutcome reconcilePayment(RunContext run, SettlementReport.Line line, String reference) {
        Optional<InternalItem> found = payments.findPayment(run.merchantId, run.provider, line.providerReference(), line.merchantReference());
        if (found.isEmpty()) {
            run.open("MISSING_INTERNALLY", reference, null, null, line.amount().amount(),
                    "The PSP settled a capture the gateway has no record of");
            return new LineOutcome(Result.EXCEPTION, null);
        }
        InternalItem item = found.get();
        if (!item.amount().equals(line.amount())) {
            run.open("AMOUNT_MISMATCH", reference, item.entityId(), item.amount().amount(), line.amount().amount(),
                    "The PSP settled a different amount than the attempt");
            return new LineOutcome(Result.EXCEPTION, item);
        }
        if (item.succeeded()) {
            return new LineOutcome(Result.MATCHED, item);
        }
        InternalItem healed = payments.healPayment(item, line.providerReference(), line.amount());
        if (healed.succeeded()) {
            log.info("Reconciliation healed attempt {} from {} to SUCCEEDED", item.entityId(), item.status());
            return new LineOutcome(Result.AUTO_HEALED, healed);
        }
        run.open("STATUS_MISMATCH", reference, item.entityId(), null, line.amount().amount(),
                "The PSP settled a capture but the attempt is " + healed.status());
        return new LineOutcome(Result.EXCEPTION, item);
    }

    private LineOutcome reconcileRefund(RunContext run, SettlementReport.Line line, String reference) {
        Optional<InternalItem> found = payments.findRefund(run.merchantId, run.provider, line.providerReference(), line.merchantReference());
        if (found.isEmpty()) {
            run.open("MISSING_INTERNALLY", reference, null, null, line.amount().amount(),
                    "The PSP settled a refund the gateway has no record of");
            return new LineOutcome(Result.EXCEPTION, null);
        }
        InternalItem item = found.get();
        if (!item.amount().equals(line.amount())) {
            run.open("AMOUNT_MISMATCH", reference, item.entityId(), item.amount().amount(), line.amount().amount(),
                    "The PSP settled a different refund amount");
            return new LineOutcome(Result.EXCEPTION, item);
        }
        if (item.succeeded()) {
            return new LineOutcome(Result.MATCHED, item);
        }
        InternalItem healed = payments.healRefund(item, line.providerReference(), line.amount());
        if (healed.succeeded()) {
            return new LineOutcome(Result.AUTO_HEALED, healed);
        }
        run.open("STATUS_MISMATCH", reference, item.entityId(), null, line.amount().amount(),
                "The PSP settled a refund but it is " + healed.status());
        return new LineOutcome(Result.EXCEPTION, item);
    }

    /** A disputed amount the PSP withheld: matched to the dispute, or the dispute is recorded from the report. */
    private LineOutcome reconcileChargeback(RunContext run, SettlementReport.Line line, String reference) {
        Optional<InternalItem> found = payments.findDispute(run.merchantId, run.provider, line.providerReference());
        if (found.isEmpty()) {
            Optional<InternalItem> ingested = payments.ingestDispute(run.merchantId, run.provider, line.providerReference(),
                    line.merchantReference(), line.amount());
            if (ingested.isEmpty()) {
                run.open("MISSING_INTERNALLY", reference, null, null, line.amount().amount(),
                        "The PSP withheld a chargeback on a payment the gateway has no record of");
                return new LineOutcome(Result.EXCEPTION, null);
            }
            log.info("Reconciliation recorded dispute {} seen only in the settlement report", ingested.get().entityId());
            return new LineOutcome(Result.AUTO_HEALED, ingested.get());
        }
        if (!found.get().amount().equals(line.amount())) {
            run.open("AMOUNT_MISMATCH", reference, found.get().entityId(), found.get().amount().amount(),
                    line.amount().amount(), "The PSP withheld a different amount than the dispute");
            return new LineOutcome(Result.EXCEPTION, found.get());
        }
        return new LineOutcome(Result.MATCHED, found.get());
    }

    /** Funds returned after a won dispute. */
    private LineOutcome reconcileChargebackReversal(RunContext run, SettlementReport.Line line, String reference) {
        Optional<InternalItem> found = payments.findDispute(run.merchantId, run.provider, line.providerReference());
        if (found.isEmpty()) {
            run.open("MISSING_INTERNALLY", reference, null, null, line.amount().amount(),
                    "The PSP returned a chargeback the gateway has no record of");
            return new LineOutcome(Result.EXCEPTION, null);
        }
        InternalItem item = found.get();
        if (!item.amount().equals(line.amount())) {
            run.open("AMOUNT_MISMATCH", reference, item.entityId(), item.amount().amount(), line.amount().amount(),
                    "The PSP returned a different amount than the dispute");
            return new LineOutcome(Result.EXCEPTION, item);
        }
        if ("WON".equals(item.status())) {
            return new LineOutcome(Result.MATCHED, item);
        }
        InternalItem healed = payments.healDisputeWon(item);
        if ("WON".equals(healed.status())) {
            return new LineOutcome(Result.AUTO_HEALED, healed);
        }
        run.open("STATUS_MISMATCH", reference, item.entityId(), null, line.amount().amount(),
                "The PSP returned a chargeback but the dispute is " + healed.status());
        return new LineOutcome(Result.EXCEPTION, item);
    }

    private void postFee(RunContext run, SettlementReport.Line line) {
        ledger.post(new Posting(run.merchantId, run.provider, LedgerTransactionType.PSP_FEE, "REPORT_LINE",
                run.provider + ":" + line.lineId(), "PSP fee on " + line.providerReference(),
                line.occurredAt() == null ? run.now : line.occurredAt(),
                List.of(Leg.debit(LedgerAccountType.PSP_FEES, line.fee()), Leg.credit(LedgerAccountType.PSP_RECEIVABLE, line.fee()))));
    }

    /** Posts each payout and checks it against what the matched lines say it should be. */
    private void postSettlements(RunContext run, SettlementReport report) {
        for (SettlementReport.Settlement settlement : report.settlements()) {
            run.settled += settlement.netAmount();
            if (settlement.netAmount() != 0) {
                Money amount = Money.of(Math.abs(settlement.netAmount()), settlement.currency());
                List<Leg> legs = settlement.netAmount() > 0
                        ? List.of(Leg.debit(LedgerAccountType.BANK_SETTLEMENTS, amount), Leg.credit(LedgerAccountType.PSP_RECEIVABLE, amount))
                        : List.of(Leg.debit(LedgerAccountType.PSP_RECEIVABLE, amount), Leg.credit(LedgerAccountType.BANK_SETTLEMENTS, amount));
                ledger.post(new Posting(run.merchantId, run.provider, LedgerTransactionType.SETTLEMENT, "SETTLEMENT",
                        run.provider + ":" + settlement.settlementId(), "settlement " + settlement.bankReference(),
                        settlement.settledAt() == null ? run.now : settlement.settledAt(), legs));
            }
            long expected = run.internalNetBySettlement.getOrDefault(settlement.settlementId(), 0L);
            if (expected != settlement.netAmount()) {
                run.open("SETTLEMENT_MISMATCH", settlement.settlementId(), null, expected, settlement.netAmount(),
                        "Payout differs from the net of matched captures, refunds, fees and chargebacks by " + (settlement.netAmount() - expected));
            }
        }
    }

    private void flagMissingAtProvider(RunContext run, Instant from, Instant to) {
        List<InternalItem> internal = payments.succeededBetween(run.merchantId, run.provider, from, to);
        Set<String> seenByProvider = repository.entitiesSeenInReports(run.merchantId, run.provider,
                internal.stream().map(InternalItem::entityId).toList());
        for (InternalItem item : internal) {
            if (!seenByProvider.contains(item.entityId())) {
                run.open("MISSING_AT_PROVIDER", item.entityId(), item.entityId(), item.amount().amount(), null,
                        (item.kind() == PaymentReconciliationService.Kind.PAYMENT ? "Captured attempt" : "Succeeded refund")
                                + " is missing from the PSP settlement report");
            }
        }
    }

    private static ExceptionView toView(ExceptionRow row, Instant now) {
        boolean overdue = "OPEN".equals(row.status()) && row.dueAt().isBefore(now);
        return new ExceptionView(row.id(), row.runId(), row.merchantId(), row.providerCode(),
                row.type().toLowerCase(Locale.ROOT), row.reference(), row.entityId(), row.expectedAmount(),
                row.actualAmount(), row.details(), row.status().toLowerCase(Locale.ROOT), row.resolution(),
                row.createdAt(), row.resolvedAt(), row.dueAt(), overdue, row.assignee(), row.assignedAt());
    }
}
