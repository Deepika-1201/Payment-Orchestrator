package com.payments.gateway.billing;

import com.payments.gateway.billing.BillingAmounts.Price;
import com.payments.gateway.billing.BillingDocuments.Invoice;
import com.payments.gateway.billing.BillingDocuments.InvoiceLine;
import com.payments.gateway.billing.BillingDocuments.CreditLine;
import com.payments.gateway.billing.BillingDocuments.CreditNote;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.events.BillableActivity.Kind;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BillingRepository {

    public record Account(String merchantId, Instant enrolledAt, YearMonth nextMonth) {
    }

    private record PlanRow(String id, String merchantId, String name, Instant effectiveAt, long minimum, Instant createdAt) {
    }

    private record PriceRow(String kind, MethodType method, Price price) {
    }

        public record ChargeGroup(String planId, String kind, String method, long quantity, long amount,
                                                           Instant firstAt, Instant lastAt) {
        }

    private final JdbcClient jdbc;

    public BillingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account enroll(String merchantId, Instant at) {
        jdbc.sql("""
                INSERT INTO billing_accounts (merchant_id, enrolled_at, next_month) VALUES (:merchant, :at, :month)
                ON CONFLICT (merchant_id) DO NOTHING
                """).param("merchant", merchantId).param("at", Sql.ts(at))
                .param("month", YearMonth.from(at.atZone(BillingAmounts.ZONE)).atDay(1)).update();
        return lockAccount(merchantId).orElseThrow();
    }

    public Optional<Account> lockAccount(String merchantId) {
        return jdbc.sql("SELECT * FROM billing_accounts WHERE merchant_id = :merchant FOR UPDATE")
                .param("merchant", merchantId).query((result, row) -> new Account(result.getString("merchant_id"),
                        Sql.instant(result, "enrolled_at"), YearMonth.from(result.getObject("next_month", LocalDate.class))))
                .optional();
    }

    public void insertPlan(BillingPlan plan) {
        jdbc.sql("""
                INSERT INTO billing_plans (id, merchant_id, name, effective_at, monthly_minimum, created_at)
                VALUES (:id, :merchant, :name, :effective, :minimum, :created)
                """).param("id", plan.id()).param("merchant", plan.merchantId()).param("name", plan.name())
                .param("effective", Sql.ts(plan.effectiveAt())).param("minimum", plan.monthlyMinimum())
                .param("created", Sql.ts(plan.createdAt())).update();
        insertPrices(plan.id(), "PAYMENT", plan.paymentFees());
        insertPrices(plan.id(), "REFUND", plan.refundFees());
    }

    private void insertPrices(String planId, String kind, Map<MethodType, Price> prices) {
        prices.forEach((method, price) -> jdbc.sql("""
                INSERT INTO billing_plan_prices (plan_id, kind, method, fixed_amount, rate_bps)
                VALUES (:plan, :kind, :method, :fixed, :rate)
                """).param("plan", planId).param("kind", kind).param("method", method.name())
                .param("fixed", price.fixedAmount()).param("rate", price.rateBps()).update());
    }

    public Optional<BillingPlan> effective(String merchantId, Instant at) {
        return jdbc.sql("""
                SELECT * FROM billing_plans WHERE merchant_id = :merchant AND effective_at <= :at
                 ORDER BY effective_at DESC LIMIT 1
                """).param("merchant", merchantId).param("at", Sql.ts(at)).query(BillingRepository::planRow)
                .optional().map(this::plan);
    }

    public BillingPlan plan(String id) {
        return plan(jdbc.sql("SELECT * FROM billing_plans WHERE id = :id").param("id", id)
                .query(BillingRepository::planRow).single());
    }

    public List<BillingPlan> plans(String merchantId) {
        return jdbc.sql("SELECT * FROM billing_plans WHERE merchant_id = :merchant ORDER BY effective_at DESC LIMIT 500")
                .param("merchant", merchantId).query(BillingRepository::planRow).list().stream().map(this::plan).toList();
    }

    private BillingPlan plan(PlanRow row) {
        Map<MethodType, Price> payments = new EnumMap<>(MethodType.class);
        Map<MethodType, Price> refunds = new EnumMap<>(MethodType.class);
        jdbc.sql("SELECT * FROM billing_plan_prices WHERE plan_id = :id").param("id", row.id())
                .query((result, index) -> new PriceRow(result.getString("kind"), MethodType.valueOf(result.getString("method")),
                        new Price(result.getLong("fixed_amount"), result.getInt("rate_bps"))))
                .list().forEach(price -> (price.kind().equals("PAYMENT") ? payments : refunds).put(price.method(), price.price()));
        return new BillingPlan(row.id(), row.merchantId(), row.name(), row.effectiveAt(), payments, refunds,
                row.minimum(), row.createdAt());
    }

        public void insertEvent(BillingEvent event) {
                jdbc.sql("""
                                INSERT INTO billing_events (id, merchant_id, kind, reference_id, payment_id, plan_id, method,
                                                                                        amount, currency, settled_amount, service_fee, recognized_at)
                                VALUES (:id, :merchant, :kind, :reference, :payment, :plan, :method, :amount, :currency, :settled, :fee, :at)
                                ON CONFLICT (kind, reference_id) DO NOTHING
                                """).param("id", event.id()).param("merchant", event.merchantId()).param("kind", event.kind().name())
                                .param("reference", event.referenceId()).param("payment", event.paymentId()).param("plan", event.planId())
                                .param("method", event.method().name()).param("amount", event.amount().amount()).param("currency", event.amount().currency())
                                .param("settled", event.settledAmount()).param("fee", event.serviceFee()).param("at", Sql.ts(event.recognizedAt())).update();
        }

        public Optional<BillingEvent> event(Kind kind, String referenceId, boolean lock) {
                return jdbc.sql("SELECT * FROM billing_events WHERE kind = :kind AND reference_id = :reference" + (lock ? " FOR UPDATE" : ""))
                                .param("kind", kind.name()).param("reference", referenceId).query(BillingRepository::eventRow).optional();
        }

        public Optional<BillingEvent> lockEvent(String merchantId, String id) {
                return jdbc.sql("SELECT * FROM billing_events WHERE merchant_id = :merchant AND id = :id FOR UPDATE")
                                .param("merchant", merchantId).param("id", id).query(BillingRepository::eventRow).optional();
        }

        public void completeEvent(String id, long settled, Long fee, String reason) {
                jdbc.sql("""
                                UPDATE billing_events SET settled_amount = :settled, service_fee = :fee, price_reason = :reason WHERE id = :id
                                """).param("id", id).param("settled", settled).param("fee", fee).param("reason", reason).update();
        }

        public List<BillingEvent> events(String merchantId, int limit) {
                return jdbc.sql("SELECT * FROM billing_events WHERE merchant_id = :merchant ORDER BY recognized_at, id LIMIT :limit")
                                .param("merchant", merchantId).param("limit", limit).query(BillingRepository::eventRow).list();
        }

        private static BillingEvent eventRow(ResultSet result, int row) throws SQLException {
                return new BillingEvent(result.getString("id"), result.getString("merchant_id"), Kind.valueOf(result.getString("kind")),
                                result.getString("reference_id"), result.getString("payment_id"), result.getString("plan_id"),
                                MethodType.valueOf(result.getString("method")), Money.of(result.getLong("amount"), result.getString("currency")),
                                Sql.nullableLong(result, "settled_amount"), Sql.nullableLong(result, "service_fee"),
                                result.getString("price_reason"), Sql.instant(result, "recognized_at"));
        }

        private static PlanRow planRow(ResultSet result, int row) throws SQLException {
        return new PlanRow(result.getString("id"), result.getString("merchant_id"), result.getString("name"),
                Sql.instant(result, "effective_at"), result.getLong("monthly_minimum"), Sql.instant(result, "created_at"));
    }

    public List<BillingEvent> events(String merchantId, boolean unbilled, int limit) {
        return jdbc.sql("""
                SELECT event.*, assignment.invoice_id FROM billing_events event
                  LEFT JOIN billing_event_assignments assignment ON assignment.event_id = event.id
                 WHERE event.merchant_id = :merchant AND (:unbilled = false OR assignment.event_id IS NULL)
                 ORDER BY event.recognized_at, event.id LIMIT :limit
                """).param("merchant", merchantId).param("unbilled", unbilled).param("limit", limit)
                .query((result, row) -> eventRow(result, row).withInvoice(result.getString("invoice_id"))).list();
    }

    public List<Account> dueAccounts(YearMonth closedMonth, String afterMerchant, int limit) {
        return jdbc.sql("""
                SELECT * FROM billing_accounts WHERE next_month <= :month AND merchant_id > :after
                 ORDER BY merchant_id LIMIT :limit
                """).param("month", closedMonth.atDay(1)).param("after", afterMerchant).param("limit", limit)
                .query((result, row) -> new Account(result.getString("merchant_id"), Sql.instant(result, "enrolled_at"),
                        YearMonth.from(result.getObject("next_month", LocalDate.class)))).list();
    }

    public List<BillingEvent> blockers(String merchantId, Instant end) {
        return jdbc.sql("""
                SELECT event.* FROM billing_events event WHERE merchant_id = :merchant AND recognized_at < :end
                 AND (settled_amount IS NULL OR service_fee IS NULL)
                 AND NOT EXISTS (SELECT 1 FROM billing_event_assignments WHERE event_id = event.id)
                 ORDER BY recognized_at, id LIMIT 20
                """).param("merchant", merchantId).param("end", Sql.ts(end)).query(BillingRepository::eventRow).list();
    }

    public List<ChargeGroup> charges(String merchantId, Instant end) {
        return jdbc.sql("""
                SELECT plan_id, kind, method, count(*) AS quantity, sum(service_fee) AS amount,
                       min(recognized_at) AS first_at, max(recognized_at) AS last_at
                  FROM billing_events event WHERE merchant_id = :merchant AND recognized_at < :end
                   AND NOT EXISTS (SELECT 1 FROM billing_event_assignments WHERE event_id = event.id)
                 GROUP BY plan_id, kind, method ORDER BY plan_id, kind, method
                """).param("merchant", merchantId).param("end", Sql.ts(end))
                .query((result, row) -> new ChargeGroup(result.getString("plan_id"), result.getString("kind"), result.getString("method"),
                        result.getLong("quantity"), result.getLong("amount"), Sql.instant(result, "first_at"), Sql.instant(result, "last_at"))).list();
    }

    public long nextNumber(String series, int year) {
        jdbc.sql("INSERT INTO billing_series (series, financial_year, last_value) VALUES (:series, :year, 0) ON CONFLICT DO NOTHING")
                .param("series", series).param("year", year).update();
        return jdbc.sql("""
                UPDATE billing_series SET last_value = last_value + 1
                 WHERE series = :series AND financial_year = :year AND last_value < :max RETURNING last_value
                """).param("series", series).param("year", year).param("max", BillingAmounts.MAX_SEQUENCE).query(Long.class)
                .optional().orElseThrow(() -> new GatewayException(ErrorCode.BILLING_INVALID_STATE, "Billing number series is exhausted"));
    }

    public void insertInvoice(Invoice invoice, String minimumPlanId, int year, long sequence) {
        jdbc.sql("""
                INSERT INTO billing_invoices (id, merchant_id, month, number, financial_year, sequence, minimum_plan_id,
                                               subtotal, tax, total, collection_mode, issued_at)
                VALUES (:id, :merchant, :month, :number, :year, :sequence, :plan, :subtotal, :tax, :total, :mode, :at)
                """).param("id", invoice.id()).param("merchant", invoice.merchantId()).param("month", invoice.month().atDay(1))
                .param("number", invoice.number()).param("year", year).param("sequence", sequence).param("plan", minimumPlanId)
                .param("subtotal", invoice.subtotal()).param("tax", invoice.tax()).param("total", invoice.total())
                .param("mode", invoice.collectionMode()).param("at", Sql.ts(invoice.issuedAt())).update();
        for (int position = 0; position < invoice.lines().size(); position++) {
            InvoiceLine line = invoice.lines().get(position);
            jdbc.sql("""
                    INSERT INTO billing_invoice_lines (id, invoice_id, merchant_id, position, kind, plan_id, method,
                                                        quantity, amount, tax, first_activity_at, last_activity_at)
                    VALUES (:id, :invoice, :merchant, :position, :kind, :plan, :method, :quantity, :amount, :tax, :first, :last)
                    """).param("id", line.id()).param("invoice", invoice.id()).param("merchant", invoice.merchantId())
                    .param("position", position).param("kind", line.kind()).param("plan", line.planId()).param("method", line.method())
                    .param("quantity", line.quantity()).param("amount", line.amount()).param("tax", line.tax())
                    .param("first", Sql.ts(line.firstActivityAt())).param("last", Sql.ts(line.lastActivityAt())).update();
            if (!line.kind().equals("MINIMUM")) {
                jdbc.sql("""
                        INSERT INTO billing_event_assignments (event_id, merchant_id, invoice_id, line_id)
                        SELECT id, merchant_id, :invoice, :line FROM billing_events event
                         WHERE merchant_id = :merchant AND plan_id = :plan AND kind = :kind AND method = :method
                           AND recognized_at < :end
                           AND NOT EXISTS (SELECT 1 FROM billing_event_assignments WHERE event_id = event.id)
                        """).param("invoice", invoice.id()).param("line", line.id()).param("merchant", invoice.merchantId())
                        .param("plan", line.planId()).param("kind", line.kind()).param("method", line.method())
                        .param("end", Sql.ts(invoice.month().plusMonths(1).atDay(1).atStartOfDay(BillingAmounts.ZONE).toInstant())).update();
            }
        }
        jdbc.sql("UPDATE billing_accounts SET next_month = :next WHERE merchant_id = :merchant")
                .param("next", invoice.month().plusMonths(1).atDay(1)).param("merchant", invoice.merchantId()).update();
    }

    public Optional<Invoice> invoice(String merchantId, YearMonth month) {
        return jdbc.sql("SELECT * FROM billing_invoices WHERE merchant_id = :merchant AND month = :month")
                .param("merchant", merchantId).param("month", month.atDay(1)).query(BillingRepository::invoiceHeader).optional().map(this::withLines);
    }

    public Optional<Invoice> invoice(String merchantId, String id) {
        return jdbc.sql("SELECT * FROM billing_invoices WHERE merchant_id = :merchant AND id = :id")
                .param("merchant", merchantId).param("id", id).query(BillingRepository::invoiceHeader).optional().map(this::withLines);
    }

    public List<Invoice> invoices(String merchantId) {
        return jdbc.sql("SELECT * FROM billing_invoices WHERE merchant_id = :merchant ORDER BY month DESC LIMIT 500")
                .param("merchant", merchantId).query(BillingRepository::invoiceHeader).list().stream().map(this::withLines).toList();
    }

    private Invoice withLines(Invoice header) {
        List<InvoiceLine> lines = jdbc.sql("SELECT * FROM billing_invoice_lines WHERE invoice_id = :id ORDER BY position")
                .param("id", header.id()).query((result, row) -> new InvoiceLine(result.getString("id"), result.getString("kind"),
                        result.getString("plan_id"), result.getString("method"), result.getLong("quantity"), result.getLong("amount"),
                        result.getLong("tax"), Sql.instant(result, "first_activity_at"), Sql.instant(result, "last_activity_at"))).list();
        return new Invoice(header.id(), header.object(), header.merchantId(), header.number(), header.month(), header.currency(),
                header.subtotal(), header.tax(), header.total(), header.collectionMode(), header.issuedAt(), lines);
    }

    public Optional<CreditNote> creditByReference(String merchantId, String reference) {
        return jdbc.sql("SELECT * FROM billing_credit_notes WHERE merchant_id = :merchant AND reference = :reference")
                .param("merchant", merchantId).param("reference", reference).query(BillingRepository::creditHeader).optional().map(this::withCreditLines);
    }

    public List<CreditNote> credits(String merchantId, String invoiceId) {
        return jdbc.sql("SELECT * FROM billing_credit_notes WHERE merchant_id = :merchant AND invoice_id = :invoice ORDER BY issued_at, sequence")
                .param("merchant", merchantId).param("invoice", invoiceId).query(BillingRepository::creditHeader)
                .list().stream().map(this::withCreditLines).toList();
    }

    public Map<String, Long> creditedAmounts(String invoiceId) {
        Map<String, Long> amounts = new java.util.HashMap<>();
        jdbc.sql("SELECT invoice_line_id, sum(amount) AS amount FROM billing_credit_lines WHERE invoice_id = :invoice GROUP BY invoice_line_id")
                .param("invoice", invoiceId).query((result, row) -> Map.entry(result.getString("invoice_line_id"), result.getLong("amount")))
                .list().forEach(entry -> amounts.put(entry.getKey(), entry.getValue()));
        return amounts;
    }

    public void insertCredit(CreditNote note, int year, long sequence) {
        jdbc.sql("""
                INSERT INTO billing_credit_notes (id, merchant_id, invoice_id, reference, reason, number, financial_year,
                                                   sequence, subtotal, tax, total, issued_at)
                VALUES (:id, :merchant, :invoice, :reference, :reason, :number, :year, :sequence, :subtotal, :tax, :total, :at)
                """).param("id", note.id()).param("merchant", note.merchantId()).param("invoice", note.invoiceId())
                .param("reference", note.reference()).param("reason", note.reason()).param("number", note.number())
                .param("year", year).param("sequence", sequence).param("subtotal", note.subtotal()).param("tax", note.tax())
                .param("total", note.total()).param("at", Sql.ts(note.issuedAt())).update();
        for (CreditLine line : note.lines()) {
            jdbc.sql("""
                    INSERT INTO billing_credit_lines (id, credit_note_id, invoice_id, merchant_id, invoice_line_id, amount, tax)
                    VALUES (:id, :credit, :invoice, :merchant, :line, :amount, :tax)
                    """).param("id", line.id()).param("credit", note.id()).param("invoice", note.invoiceId())
                    .param("merchant", note.merchantId()).param("line", line.invoiceLineId()).param("amount", line.amount())
                    .param("tax", line.tax()).update();
        }
    }

    private CreditNote withCreditLines(CreditNote header) {
        List<CreditLine> lines = jdbc.sql("SELECT * FROM billing_credit_lines WHERE credit_note_id = :id ORDER BY invoice_line_id")
                .param("id", header.id()).query((result, row) -> new CreditLine(result.getString("id"), result.getString("invoice_line_id"),
                        result.getLong("amount"), result.getLong("tax"))).list();
        return new CreditNote(header.id(), header.object(), header.merchantId(), header.invoiceId(), header.reference(), header.reason(),
                header.number(), header.currency(), header.subtotal(), header.tax(), header.total(), header.issuedAt(), lines);
    }

    private static CreditNote creditHeader(ResultSet result, int row) throws SQLException {
        return new CreditNote(result.getString("id"), "billing_credit_note", result.getString("merchant_id"), result.getString("invoice_id"),
                result.getString("reference"), result.getString("reason"), result.getString("number"), result.getString("currency"),
                result.getLong("subtotal"), result.getLong("tax"), result.getLong("total"), Sql.instant(result, "issued_at"), List.of());
    }

    private static Invoice invoiceHeader(ResultSet result, int row) throws SQLException {
        return new Invoice(result.getString("id"), "billing_invoice", result.getString("merchant_id"), result.getString("number"),
                YearMonth.from(result.getObject("month", LocalDate.class)), result.getString("currency"), result.getLong("subtotal"),
                result.getLong("tax"), result.getLong("total"), result.getString("collection_mode"), Sql.instant(result, "issued_at"), List.of());
    }
}