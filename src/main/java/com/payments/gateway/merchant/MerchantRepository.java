package com.payments.gateway.merchant;

import com.payments.gateway.shared.jdbc.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MerchantRepository {

    private final JdbcClient jdbc;

    public MerchantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Merchant merchant, byte[] webhookSecretEncrypted) {
        jdbc.sql("""
                INSERT INTO merchants (id, name, status, webhook_url, webhook_secret_enc, late_success_policy,
                                       payment_expiry_seconds, created_at, updated_at)
                VALUES (:id, :name, :status, :webhookUrl, :secret, :policy, :expiry, :createdAt, :createdAt)
                """)
                .param("id", merchant.id())
                .param("name", merchant.name())
                .param("status", merchant.status().name())
                .param("webhookUrl", merchant.webhookUrl())
                .param("secret", webhookSecretEncrypted)
                .param("policy", merchant.lateSuccessPolicy().name())
                .param("expiry", (int) merchant.paymentExpiry().toSeconds())
                .param("createdAt", Sql.ts(merchant.createdAt()))
                .update();
    }

    public Optional<Merchant> findById(String id) {
        return jdbc.sql("SELECT * FROM merchants WHERE id = :id")
                .param("id", id)
                .query(MerchantRepository::mapMerchant)
                .optional();
    }

    public void insertApiKey(String id, String merchantId, byte[] keyHash, String hint, Instant now) {
        jdbc.sql("""
                INSERT INTO api_keys (id, merchant_id, key_hash, key_hint, mode, status, created_at)
                VALUES (:id, :merchantId, :hash, :hint, 'TEST', 'ACTIVE', :now)
                """)
                .param("id", id)
                .param("merchantId", merchantId)
                .param("hash", keyHash)
                .param("hint", hint)
                .param("now", Sql.ts(now))
                .update();
    }

    public Optional<MerchantPrincipal> findPrincipalByKeyHash(byte[] keyHash) {
        return jdbc.sql("""
                SELECT k.id AS key_id, k.merchant_id
                  FROM api_keys k JOIN merchants m ON m.id = k.merchant_id
                 WHERE k.key_hash = :hash AND k.status = 'ACTIVE' AND m.status = 'ACTIVE'
                """)
                .param("hash", keyHash)
                .query((rs, n) -> new MerchantPrincipal(rs.getString("merchant_id"), rs.getString("key_id")))
                .optional();
    }

    public void insertProviderAccount(String id, String merchantId, String providerCode, Instant now) {
        jdbc.sql("""
                INSERT INTO merchant_provider_accounts (id, merchant_id, provider_code, status, created_at)
                VALUES (:id, :merchantId, :provider, 'ACTIVE', :now)
                """)
                .param("id", id)
                .param("merchantId", merchantId)
                .param("provider", providerCode)
                .param("now", Sql.ts(now))
                .update();
    }

    public List<String> findActiveProviderCodes(String merchantId) {
        return jdbc.sql("""
                SELECT provider_code FROM merchant_provider_accounts
                 WHERE merchant_id = :merchantId AND status = 'ACTIVE' ORDER BY provider_code
                """)
                .param("merchantId", merchantId)
                .query(String.class)
                .list();
    }

    public Optional<byte[]> findWebhookSecret(String merchantId) {
        return jdbc.sql("SELECT webhook_secret_enc FROM merchants WHERE id = :id AND webhook_secret_enc IS NOT NULL")
                .param("id", merchantId)
                .query((rs, n) -> rs.getBytes(1))
                .optional();
    }

    private static Merchant mapMerchant(ResultSet rs, int rowNum) throws SQLException {
        return new Merchant(
                rs.getString("id"),
                rs.getString("name"),
                Merchant.Status.valueOf(rs.getString("status")),
                rs.getString("webhook_url"),
                Merchant.LateSuccessPolicy.valueOf(rs.getString("late_success_policy")),
                Duration.ofSeconds(rs.getInt("payment_expiry_seconds")),
                Sql.instant(rs, "created_at"));
    }
}
