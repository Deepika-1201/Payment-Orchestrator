package com.payments.gateway.routing;

import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.MethodType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProviderFeeScheduleRepository {

    private final JdbcClient jdbc;

    public ProviderFeeScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ProviderFeeSchedule schedule) {
        jdbc.sql("""
                INSERT INTO provider_fee_schedules (id, merchant_id, provider_code, method, currency, fixed_amount,
                                                     rate_bps, tax_bps, collection, effective_at, created_at)
                VALUES (:id, :merchant, :provider, :method, :currency, :fixed, :rate, :tax, :collection, :effective, :created)
                """)
                .param("id", schedule.id()).param("merchant", schedule.merchantId()).param("provider", schedule.providerCode())
                .param("method", schedule.method().name()).param("currency", schedule.currency())
                .param("fixed", schedule.fixedAmount()).param("rate", schedule.rateBps()).param("tax", schedule.taxBps())
                .param("collection", schedule.collection().name()).param("effective", Sql.ts(schedule.effectiveAt()))
                .param("created", Sql.ts(schedule.createdAt())).update();
    }

    public List<ProviderFeeSchedule> history(String merchantId, String providerCode) {
        return jdbc.sql("""
                SELECT * FROM provider_fee_schedules WHERE merchant_id = :merchant AND provider_code = :provider
                 ORDER BY effective_at DESC, id DESC LIMIT 500
                """).param("merchant", merchantId).param("provider", providerCode).query(ProviderFeeScheduleRepository::map).list();
    }

    public Map<String, ProviderFeeSchedule> effective(String merchantId, Collection<String> providers,
                                                      MethodType method, String currency, Instant at) {
        if (providers.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT ON (provider_code) * FROM provider_fee_schedules
                 WHERE merchant_id = :merchant AND provider_code IN (:providers) AND method = :method
                   AND currency = :currency AND effective_at <= :at
                 ORDER BY provider_code, effective_at DESC
                """).param("merchant", merchantId).param("providers", providers).param("method", method.name())
                .param("currency", currency).param("at", Sql.ts(at)).query(ProviderFeeScheduleRepository::map).list()
                .stream().collect(Collectors.toUnmodifiableMap(ProviderFeeSchedule::providerCode, schedule -> schedule));
    }

    private static ProviderFeeSchedule map(ResultSet result, int rowNumber) throws SQLException {
        return new ProviderFeeSchedule(result.getString("id"), result.getString("merchant_id"), result.getString("provider_code"),
                MethodType.valueOf(result.getString("method")), result.getString("currency"), result.getLong("fixed_amount"),
                result.getInt("rate_bps"), result.getInt("tax_bps"), ProviderFeeSchedule.Collection.valueOf(result.getString("collection")),
                Sql.instant(result, "effective_at"), Sql.instant(result, "created_at"));
    }
}