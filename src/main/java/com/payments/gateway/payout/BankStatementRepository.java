package com.payments.gateway.payout;

import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.json.JsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BankStatementRepository {
    private record Header(String id, String reference, String accountReference, Instant from, Instant to,
                          long openingBalance, long closingBalance, Instant importedAt) {
    }

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public BankStatementRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<BankStatement> byReference(String reference) {
        return jdbc.sql("SELECT * FROM escrow_bank_statements WHERE reference = :reference").param("reference", reference)
                .query(BankStatementRepository::header).optional().map(this::entries);
    }

    public Optional<BankStatement> find(String id) {
        return jdbc.sql("SELECT * FROM escrow_bank_statements WHERE id = :id").param("id", id)
                .query(BankStatementRepository::header).optional().map(this::entries);
    }

    public void insert(BankStatement statement, String actor) {
        jdbc.sql("""
                INSERT INTO escrow_bank_statements (id, reference, account_reference, window_start, window_end, opening_balance,
                                                    closing_balance, imported_at, imported_by)
                VALUES (:id, :reference, :account, :from, :to, :opening, :closing, :now, :actor)
                """).param("id", statement.id()).param("reference", statement.reference()).param("account", statement.accountReference())
                .param("from", Sql.ts(statement.from())).param("to", Sql.ts(statement.to())).param("opening", statement.openingBalance())
                .param("closing", statement.closingBalance()).param("now", Sql.ts(statement.importedAt())).param("actor", actor).update();
        for (BankStatement.Entry entry : statement.entries()) {
            jdbc.sql("""
                    INSERT INTO escrow_bank_entries (statement_id, bank_entry_id, kind, reference_id, amount, booked_at)
                    VALUES (:statement, :id, :kind, :reference, :amount, :now)
                    """).param("statement", statement.id()).param("id", entry.bankEntryId()).param("kind", entry.kind().name())
                    .param("reference", entry.referenceId()).param("amount", entry.amount()).param("now", Sql.ts(entry.bookedAt())).update();
        }
    }

    public void result(BankReconciliationService.Result result, List<BankReconciliationService.Transit> transit) {
        jdbc.sql("""
                INSERT INTO escrow_reconciliation_results (id, statement_id, ledger_balance, bank_balance, transit_amount,
                                                           difference, matched, issues, in_transit, created_at)
                VALUES (:id, :statement, :ledger, :bank, :transit, :difference, :matched, CAST(:issues AS jsonb), CAST(:items AS jsonb), :now)
                """).param("id", result.id()).param("statement", result.statementId()).param("ledger", result.ledgerBalance())
                .param("bank", result.bankBalance()).param("transit", result.transitAmount()).param("difference", result.difference())
                .param("matched", result.matched()).param("issues", json.write(result.issues())).param("items", json.write(transit))
                .param("now", Sql.ts(result.createdAt())).update();
    }

    public long countForAccount(String account) {
        return jdbc.sql("SELECT count(*) FROM escrow_bank_statements WHERE account_reference = :account").param("account", account).query(Long.class).single();
    }

    public Optional<Instant> latestEnd() {
        return jdbc.sql("SELECT max(window_end) FROM escrow_bank_statements")
                .query((row, index) -> Sql.instant(row, "max")).optional();
    }

    public record PreviousEntry(String statementId, BankStatement.Entry entry) {
    }

    public List<PreviousEntry> previousEntries(String accountReference, Instant before) {
        return jdbc.sql("""
                SELECT DISTINCT ON (entry.bank_entry_id) entry.* FROM escrow_bank_entries entry
                  JOIN escrow_bank_statements statement ON statement.id = entry.statement_id
                 WHERE statement.account_reference = :account AND statement.window_end <= :before
                 ORDER BY entry.bank_entry_id, statement.imported_at DESC, statement.id DESC
                """).param("account", accountReference).param("before", Sql.ts(before))
                .query((row, index) -> new PreviousEntry(row.getString("statement_id"), new BankStatement.Entry(row.getString("bank_entry_id"),
                        BankStatement.Kind.valueOf(row.getString("kind")), row.getString("reference_id"), row.getLong("amount"), Sql.instant(row, "booked_at")))).list();
    }

    public Optional<Long> previousClosing(String accountReference, Instant before) {
        return jdbc.sql("""
                SELECT closing_balance FROM escrow_bank_statements WHERE account_reference = :account AND window_end = :before
                 ORDER BY imported_at DESC, id DESC LIMIT 1
                """).param("account", accountReference).param("before", Sql.ts(before)).query(Long.class).optional();
    }

    public boolean hasCurrentMatchedStatement(String account, Instant since) {
        return jdbc.sql("""
                SELECT coalesce((SELECT result.matched FROM escrow_bank_statements statement
                    LEFT JOIN LATERAL (SELECT matched FROM escrow_reconciliation_results WHERE statement_id = statement.id
                                        ORDER BY created_at DESC, id DESC LIMIT 1) result ON true
                    WHERE statement.account_reference = :account AND statement.window_end >= :since
                    ORDER BY statement.window_end DESC, statement.imported_at DESC, statement.id DESC LIMIT 1), false)
                """).param("account", account).param("since", Sql.ts(since)).query(Boolean.class).single();
    }

    public Optional<String> latestId() {
        return jdbc.sql("SELECT id FROM escrow_bank_statements ORDER BY window_end DESC, imported_at DESC, id DESC LIMIT 1")
                .query(String.class).optional();
    }

    public List<BankReconciliationService.Transit> previousTransit(String statementId) {
        return jdbc.sql("SELECT in_transit::text FROM escrow_reconciliation_results WHERE statement_id = :id ORDER BY created_at DESC, id DESC LIMIT 1")
                .param("id", statementId).query(String.class).optional()
                .map(encoded -> json.read(encoded, new tools.jackson.core.type.TypeReference<List<BankReconciliationService.Transit>>() { }))
                .orElse(List.of());
    }

    public Optional<EscrowAdjustmentService.Adjustment> adjustment(String id, boolean reference) {
        return jdbc.sql("SELECT * FROM escrow_adjustments WHERE " + (reference ? "reference" : "id") + " = :id")
                .param("id", id).query(BankStatementRepository::adjustment).optional();
    }

    public void adjustment(EscrowAdjustmentService.Adjustment adjustment) {
        jdbc.sql("""
                INSERT INTO escrow_adjustments (id, reference, owner_id, kind, amount, statement_id, bank_entry_id, reason, status, created_by, created_at)
                VALUES (:id, :reference, :owner, :kind, :amount, :statement, :entry, :reason, 'PENDING', :actor, :now)
                """).param("id", adjustment.id()).param("reference", adjustment.reference()).param("owner", adjustment.ownerId())
                .param("kind", adjustment.kind().name()).param("amount", adjustment.amount()).param("statement", adjustment.statementId())
                .param("entry", adjustment.bankEntryId()).param("reason", adjustment.reason()).param("actor", adjustment.createdBy())
                .param("now", Sql.ts(adjustment.createdAt())).update();
    }

    public void decideAdjustment(String id, String decision, String actor, Instant now) {
        jdbc.sql("UPDATE escrow_adjustments SET status = :status, decided_by = :actor, decided_at = :now WHERE id = :id AND status = 'PENDING'")
                .param("status", decision).param("actor", actor).param("now", Sql.ts(now)).param("id", id).update();
    }

    public Optional<String> adjustmentForEntry(String statementId, String entryId) {
        return jdbc.sql("""
            SELECT id FROM escrow_adjustments WHERE bank_entry_id = :entry AND status = 'APPROVED'
              AND EXISTS (SELECT 1 FROM escrow_bank_entries WHERE statement_id = :statement AND bank_entry_id = :entry)
            """)
                .param("statement", statementId).param("entry", entryId).query(String.class).optional();
    }

    private static EscrowAdjustmentService.Adjustment adjustment(ResultSet row, int index) throws SQLException {
        return new EscrowAdjustmentService.Adjustment(row.getString("id"), row.getString("reference"), row.getString("owner_id"),
                EscrowAdjustmentService.Kind.valueOf(row.getString("kind")), row.getLong("amount"), row.getString("statement_id"),
                row.getString("bank_entry_id"), row.getString("reason"), row.getString("status"), row.getString("created_by"),
                Sql.instant(row, "created_at"), row.getString("decided_by"), Sql.instant(row, "decided_at"));
    }

    private BankStatement entries(Header header) {
        var entries = jdbc.sql("SELECT * FROM escrow_bank_entries WHERE statement_id = :id ORDER BY booked_at, bank_entry_id")
                .param("id", header.id()).query((row, index) -> new BankStatement.Entry(row.getString("bank_entry_id"),
                        BankStatement.Kind.valueOf(row.getString("kind")), row.getString("reference_id"), row.getLong("amount"), Sql.instant(row, "booked_at"))).list();
        return new BankStatement(header.id(), header.reference(), header.accountReference(), header.from(), header.to(),
                header.openingBalance(), header.closingBalance(), entries, header.importedAt());
    }

    private static Header header(ResultSet row, int index) throws SQLException {
        return new Header(row.getString("id"), row.getString("reference"), row.getString("account_reference"),
                Sql.instant(row, "window_start"), Sql.instant(row, "window_end"), row.getLong("opening_balance"),
                row.getLong("closing_balance"), Sql.instant(row, "imported_at"));
    }
}