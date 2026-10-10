package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.shared.events.CurrencyConversion.Kind;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.Conversion;
import com.payments.gateway.shared.model.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FxConversionRepository {

    public record Stored(String id, String paymentId, String attemptId, Kind kind, String referenceId,
                         Money amount, Conversion conversion, long carriedAmount) {
    }

    private final JdbcClient jdbc;

    public FxConversionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Stored> find(Kind kind, String referenceId) {
        return jdbc.sql("SELECT * FROM fx_conversions WHERE kind = :kind AND reference_id = :referenceId")
                .param("kind", kind.name()).param("referenceId", referenceId)
                .query(FxConversionRepository::map).optional();
    }

    public long netReturned(String attemptId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(CASE kind WHEN 'CHARGEBACK_REVERSAL' THEN -amount ELSE amount END), 0)
                  FROM fx_conversions WHERE attempt_id = :attemptId AND kind <> 'CAPTURE'
                """)
                .param("attemptId", attemptId).query(Long.class).single();
    }

    public boolean insert(Stored stored, String merchantId, String providerCode, String source, Instant now) {
        return jdbc.sql("""
                INSERT INTO fx_conversions (id, merchant_id, provider_code, payment_id, attempt_id, kind, reference_id,
                                            amount, currency, settled_amount, carried_amount, rate, source, recorded_at)
                VALUES (:id, :merchant, :provider, :payment, :attempt, :kind, :reference, :amount, :currency,
                        :settled, :carried, :rate, :source, :now)
                ON CONFLICT (kind, reference_id) DO NOTHING
                """)
                .param("id", stored.id()).param("merchant", merchantId).param("provider", providerCode)
                .param("payment", stored.paymentId()).param("attempt", stored.attemptId())
                .param("kind", stored.kind().name()).param("reference", stored.referenceId())
                .param("amount", stored.amount().amount()).param("currency", stored.amount().currency())
                .param("settled", stored.conversion().settled().amount()).param("carried", stored.carriedAmount())
                .param("rate", stored.conversion().rate()).param("source", source).param("now", Sql.ts(now))
                .update() == 1;
    }

    private static Stored map(ResultSet result, int rowNumber) throws SQLException {
        return new Stored(result.getString("id"), result.getString("payment_id"), result.getString("attempt_id"),
                Kind.valueOf(result.getString("kind")), result.getString("reference_id"),
                Money.of(result.getLong("amount"), result.getString("currency")),
                new Conversion(Money.of(result.getLong("settled_amount"), Money.SETTLEMENT_CURRENCY),
                        result.getBigDecimal("rate")), result.getLong("carried_amount"));
    }
}