package com.payments.gateway.billing;

import com.payments.gateway.billing.BillingAmounts.Price;
import com.payments.gateway.billing.BillingDocuments.Invoice;
import com.payments.gateway.billing.BillingDocuments.InvoiceLine;
import com.payments.gateway.billing.BillingDocuments.CreditLine;
import com.payments.gateway.billing.BillingDocuments.CreditNote;
import com.payments.gateway.billing.BillingRepository.ChargeGroup;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.model.MethodType;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BillingService {

    public record PublishPlan(String name, Instant effectiveAt, Map<MethodType, Price> paymentFees,
                              Map<MethodType, Price> refundFees, long monthlyMinimum) {
    }

    public record CreditAmount(String invoiceLineId, long amount) {
    }

    public record CreateCredit(String reference, String reason, List<CreditAmount> lines) {
    }

    private final BillingRepository repository;
    private final MerchantDirectory merchants;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final TransactionTemplate invoiceTx;

    public BillingService(BillingRepository repository, MerchantDirectory merchants, AuditLogger audit,
                          TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.merchants = merchants;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.invoiceTx = new TransactionTemplate(java.util.Objects.requireNonNull(tx.getTransactionManager()));
        this.invoiceTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public BillingPlan publish(String merchantId, PublishPlan command, String actor) {
        merchants.require(merchantId);
        Instant now = clock.instant();
        Instant effective = command.effectiveAt() == null ? now : command.effectiveAt().truncatedTo(ChronoUnit.MICROS);
        if (effective.isBefore(now)) {
            throw GatewayException.validation("effective_at", "cannot be backdated");
        }
        BillingPlan plan = new BillingPlan(Ids.newId("bpl"), merchantId, command.name(), effective,
                command.paymentFees(), command.refundFees(), command.monthlyMinimum(), now);
        try {
            return tx.execute(status -> {
                BillingRepository.Account account = repository.enroll(merchantId, effective);
                if (effective.isBefore(account.enrolledAt())) {
                    throw GatewayException.validation("effective_at", "cannot precede the merchant's billing enrollment");
                }
                repository.insertPlan(plan);
                audit.record("ADMIN", actor, "billing_plan.published", "billing_plan", plan.id(),
                        Map.of("merchant_id", merchantId, "effective_at", effective.toString()));
                return plan;
            });
        } catch (DuplicateKeyException exception) {
            throw invalid("A billing plan already exists for this merchant and effective timestamp");
        }
    }

    public List<BillingPlan> plans(String merchantId) {
        merchants.require(merchantId);
        return repository.plans(merchantId);
    }

    public Invoice issue(String merchantId, YearMonth month) {
        return issue(merchantId, month, null);
    }

    public Invoice issue(String merchantId, YearMonth month, String actor) {
        merchants.require(merchantId);
        Instant now = clock.instant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(BillingAmounts.ZONE).toInstant();
        if (end.isAfter(now)) {
            throw invalid("Only closed billing months can be issued");
        }
        return issueTransaction(() -> {
            BillingRepository.Account account = repository.lockAccount(merchantId)
                    .orElseThrow(() -> GatewayException.notFound("Billing account", merchantId));
            var existing = repository.invoice(merchantId, month);
            if (existing.isPresent()) {
                return existing.get();
            }
            if (!account.nextMonth().equals(month)) {
                throw invalid("Issue " + account.nextMonth() + " before another billing month");
            }
            List<BillingEvent> blockers = repository.blockers(merchantId, end);
            if (!blockers.isEmpty()) {
                throw new GatewayException(ErrorCode.BILLING_INVOICE_BLOCKED, "Invoice has activity awaiting conversion or pricing",
                        blockers.stream().map(event -> new GatewayException.FieldError(event.id(), event.pricingStatus())).toList(), null);
            }
            BillingPlan minimumPlan = repository.effective(merchantId, end.minusNanos(1000)).orElseThrow();
            List<ChargeGroup> groups = new ArrayList<>(repository.charges(merchantId, end));
            long charges = 0;
            for (ChargeGroup group : groups) {
                charges = Math.addExact(charges, group.amount());
            }
            long minimum = Math.max(0, minimumPlan.monthlyMinimum() - charges);
            if (minimum > 0) {
                groups.add(new ChargeGroup(minimumPlan.id(), "MINIMUM", null, 1, minimum, null, null));
            }
            long subtotal = Math.addExact(charges, minimum);
            long tax = BillingAmounts.tax(subtotal);
            List<InvoiceLine> lines = new ArrayList<>();
            long previous = 0;
            for (ChargeGroup group : groups) {
                long allocated = BillingAmounts.allocateTax(subtotal, tax, previous, group.amount());
                lines.add(new InvoiceLine(Ids.newId("bil"), group.kind(), group.planId(), group.method(), group.quantity(),
                        group.amount(), allocated, group.firstAt(), group.lastAt()));
                previous = Math.addExact(previous, group.amount());
            }
            int year = BillingAmounts.financialYear(now);
            long sequence = repository.nextNumber("INV", year);
            Invoice invoice = new Invoice(Ids.newId("binv"), "billing_invoice", merchantId, BillingAmounts.number("INV", year, sequence),
                    month, "INR", subtotal, tax, Math.addExact(subtotal, tax), "MERCHANT_PAYMENT", now, lines);
            repository.insertInvoice(invoice, minimumPlan.id(), year, sequence);
                audit.record(actor == null ? "SYSTEM" : "ADMIN", actor == null ? "billing-worker" : actor,
                    "billing_invoice.issued", "billing_invoice", invoice.id(),
                    Map.of("merchant_id", merchantId, "month", month.toString(), "total", invoice.total()));
            return invoice;
        });
    }

    private <T> T issueTransaction(Supplier<T> action) {
        for (int retry = 0; ; retry++) {
            try {
                return invoiceTx.execute(status -> action.get());
            } catch (ConcurrencyFailureException exception) {
                if (retry >= 4) {
                    throw exception;
                }
            }
        }
    }

    public Invoice invoice(String merchantId, String invoiceId) {
        return repository.invoice(merchantId, invoiceId).orElseThrow(() -> GatewayException.notFound("Billing invoice", invoiceId));
    }

    public List<Invoice> invoices(String merchantId) {
        merchants.require(merchantId);
        return repository.invoices(merchantId);
    }

    public List<BillingEvent> events(String merchantId, boolean unbilled, int limit) {
        merchants.require(merchantId);
        if (limit < 1 || limit > 500) {
            throw GatewayException.validation("limit", "must be between 1 and 500");
        }
        return repository.events(merchantId, unbilled, limit);
    }

    public BillingEvent price(String merchantId, String eventId, long amount, String reason, String actor) {
        return tx.execute(status -> {
            BillingEvent event = repository.lockEvent(merchantId, eventId).orElseThrow(() -> GatewayException.notFound("Billing event", eventId));
            if (event.serviceFee() != null || event.settledAmount() == null) {
                throw invalid("A price can only complete an unpriced event whose INR amount is known");
            }
            repository.completeEvent(event.id(), event.settledAmount(), amount, reason);
            audit.record("ADMIN", actor, "billing_event.priced", "billing_event", event.id(), Map.of("amount", amount, "reason", reason));
            return repository.lockEvent(merchantId, eventId).orElseThrow();
        });
    }

    public CreditNote credit(String merchantId, String invoiceId, CreateCredit command, String actor) {
        Map<String, Long> requested = new TreeMap<>();
        for (CreditAmount line : command.lines()) {
            if (line.amount() <= 0 || requested.put(line.invoiceLineId(), line.amount()) != null) {
                throw GatewayException.validation("lines", "must contain distinct invoice lines with positive amounts");
            }
        }
        if (requested.isEmpty()) {
            throw GatewayException.validation("lines", "must not be empty");
        }
        return tx.execute(status -> {
            repository.lockAccount(merchantId).orElseThrow(() -> GatewayException.notFound("Billing account", merchantId));
            var existing = repository.creditByReference(merchantId, command.reference());
            if (existing.isPresent()) {
                CreditNote note = existing.get();
                Map<String, Long> previous = note.lines().stream().collect(Collectors.toMap(CreditLine::invoiceLineId, CreditLine::amount));
                if (!note.invoiceId().equals(invoiceId) || !note.reason().equals(command.reason()) || !previous.equals(requested)) {
                    throw new GatewayException(ErrorCode.BILLING_REFERENCE_REUSE, "Credit-note reference was already used with different content");
                }
                return note;
            }
            Invoice invoice = invoice(merchantId, invoiceId);
            Map<String, InvoiceLine> original = invoice.lines().stream().collect(Collectors.toMap(InvoiceLine::id, line -> line));
            Map<String, Long> credited = repository.creditedAmounts(invoiceId);
            List<CreditLine> lines = new ArrayList<>();
            long subtotal = 0;
            long tax = 0;
            for (Map.Entry<String, Long> item : requested.entrySet()) {
                InvoiceLine line = original.get(item.getKey());
                if (line == null) {
                    throw GatewayException.validation("invoice_line_id", "does not belong to this invoice");
                }
                long previous = credited.getOrDefault(line.id(), 0L);
                if (item.getValue() > line.amount() - previous) {
                    throw invalid("Credit exceeds the remaining taxable amount of invoice line " + line.id());
                }
                long reversedTax = BillingAmounts.allocateTax(line.amount(), line.tax(), previous, item.getValue());
                lines.add(new CreditLine(Ids.newId("bcl"), line.id(), item.getValue(), reversedTax));
                subtotal = Math.addExact(subtotal, item.getValue());
                tax = Math.addExact(tax, reversedTax);
            }
            Instant now = clock.instant();
            int year = BillingAmounts.financialYear(now);
            long sequence = repository.nextNumber("CN", year);
            CreditNote note = new CreditNote(Ids.newId("bcn"), "billing_credit_note", merchantId, invoiceId, command.reference(), command.reason(),
                    BillingAmounts.number("CN", year, sequence), "INR", subtotal, tax, Math.addExact(subtotal, tax), now, lines);
            repository.insertCredit(note, year, sequence);
            audit.record("ADMIN", actor, "billing_credit_note.issued", "billing_credit_note", note.id(),
                    Map.of("invoice_id", invoiceId, "reference", command.reference(), "reason", command.reason(), "total", note.total()));
            return note;
        });
    }

    public List<CreditNote> credits(String merchantId, String invoiceId) {
        invoice(merchantId, invoiceId);
        return repository.credits(merchantId, invoiceId);
    }

    private static GatewayException invalid(String detail) {
        return new GatewayException(ErrorCode.BILLING_INVALID_STATE, detail);
    }
}