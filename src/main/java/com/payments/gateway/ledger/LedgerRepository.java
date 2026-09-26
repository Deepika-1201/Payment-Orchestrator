package com.payments.gateway.ledger;

import com.payments.gateway.ledger.LedgerModel.AccountBalance;
import com.payments.gateway.ledger.LedgerModel.EntryView;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.ledger.LedgerModel.TransactionView;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false when a transaction for the same reference and type already exists. */
    public boolean insertTransaction(String id, Posting posting, Instant now) {
        return jdbc.sql("""
                INSERT INTO ledger_transactions (id, merchant_id, provider_code, type, reference_type, reference_id,
                                                 description, occurred_at, created_at)
                VALUES (:id, :merchantId, :provider, :type, :referenceType, :referenceId, :description, :occurredAt, :now)
                ON CONFLICT (reference_type, reference_id, type) DO NOTHING
                """)
                .param("id", id)
                .param("merchantId", posting.merchantId())
                .param("provider", posting.providerCode())
                .param("type", posting.type().name())
                .param("referenceType", posting.referenceType())
                .param("referenceId", posting.referenceId())
                .param("description", posting.description())
                .param("occurredAt", Sql.ts(posting.occurredAt()))
                .param("now", Sql.ts(now))
                .update() == 1;
    }

    public String accountId(String merchantId, String providerCode, LedgerAccountType type, String currency, Instant now) {
        jdbc.sql("""
                INSERT INTO ledger_accounts (id, merchant_id, provider_code, type, currency, normal_side, created_at)
                VALUES (:id, :merchantId, :provider, :type, :currency, :normalSide, :now)
                ON CONFLICT (merchant_id, provider_code, type, currency) DO NOTHING
                """)
                .param("id", Ids.newId("la"))
                .param("merchantId", merchantId)
                .param("provider", providerCode)
                .param("type", type.name())
                .param("currency", currency)
                .param("normalSide", type.normalSide().name())
                .param("now", Sql.ts(now))
                .update();
        return jdbc.sql("""
                SELECT id FROM ledger_accounts
                 WHERE merchant_id = :merchantId AND provider_code = :provider AND type = :type AND currency = :currency
                """)
                .param("merchantId", merchantId)
                .param("provider", providerCode)
                .param("type", type.name())
                .param("currency", currency)
                .query(String.class)
                .single();
    }

    public void insertEntry(String transactionId, String accountId, EntryDirection direction, Money amount, Instant now) {
        jdbc.sql("""
                INSERT INTO ledger_entries (transaction_id, account_id, direction, amount, currency, created_at)
                VALUES (:transactionId, :accountId, :direction, :amount, :currency, :now)
                """)
                .param("transactionId", transactionId)
                .param("accountId", accountId)
                .param("direction", direction.name())
                .param("amount", amount.amount())
                .param("currency", amount.currency())
                .param("now", Sql.ts(now))
                .update();
    }

    public List<AccountBalance> balances(String merchantId, String providerCode) {
        String sql = """
                SELECT a.provider_code, a.type, a.currency,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'DEBIT'), 0) AS debits,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'CREDIT'), 0) AS credits
                  FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                 WHERE a.merchant_id = :merchantId %s
                 GROUP BY a.id, a.provider_code, a.type, a.currency
                 ORDER BY a.provider_code, a.type, a.currency
                """.formatted(providerCode == null ? "" : "AND a.provider_code = :provider");
        var statement = jdbc.sql(sql).param("merchantId", merchantId);
        if (providerCode != null) {
            statement = statement.param("provider", providerCode);
        }
        return statement.query((rs, n) -> new AccountBalance(rs.getString("provider_code"),
                        LedgerAccountType.valueOf(rs.getString("type")), rs.getString("currency"), rs.getLong("debits"),
                        rs.getLong("credits")))
                .list();
    }

    public List<TransactionView> transactionsForReference(String referenceId) {
        record Header(String id, LedgerTransactionType type, String provider, String referenceType, String referenceId,
                      String description, Instant occurredAt) {
        }
        List<Header> headers = jdbc.sql("""
                SELECT id, type, provider_code, reference_type, reference_id, description, occurred_at
                  FROM ledger_transactions WHERE reference_id = :referenceId ORDER BY occurred_at, id
                """)
                .param("referenceId", referenceId)
                .query((rs, n) -> new Header(rs.getString("id"), LedgerTransactionType.valueOf(rs.getString("type")),
                        rs.getString("provider_code"), rs.getString("reference_type"), rs.getString("reference_id"),
                        rs.getString("description"), Sql.instant(rs, "occurred_at")))
                .list();
        Map<String, List<EntryView>> entries = new LinkedHashMap<>();
        for (Header header : headers) {
            entries.put(header.id(), jdbc.sql("""
                    SELECT a.type, e.direction, e.amount, e.currency
                      FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.account_id
                     WHERE e.transaction_id = :transactionId ORDER BY e.id
                    """)
                    .param("transactionId", header.id())
                    .query((rs, n) -> new EntryView(LedgerAccountType.valueOf(rs.getString("type")),
                            EntryDirection.valueOf(rs.getString("direction")), rs.getLong("amount"), rs.getString("currency")))
                    .list());
        }
        List<TransactionView> result = new ArrayList<>();
        for (Header header : headers) {
            result.add(new TransactionView(header.id(), header.type(), header.provider(), header.referenceType(),
                    header.referenceId(), header.description(), header.occurredAt(), entries.get(header.id())));
        }
        return result;
    }
}
