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

    public record ApiKeyMatch(MerchantPrincipal principal, Instant lastUsedAt) {
    }

    public record ApiKeyRow(String id, String hint, String mode, String status, Instant createdAt, Instant lastUsedAt,
                            Instant revokedAt) {
    }

    public record WebhookSecrets(byte[] current, byte[] previous, Instant previousExpiresAt) {
    }

    public record ProviderAccountRow(String id, String merchantId, String providerCode, String status,
                                     byte[] credentialsEncrypted, Instant credentialsUpdatedAt, Instant createdAt,
                                     Instant disabledAt) {
    }

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

    /** Row lock for read-modify-write of merchant settings and secrets. */
    public Optional<Merchant> lockById(String id) {
        return jdbc.sql("SELECT * FROM merchants WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(MerchantRepository::mapMerchant)
                .optional();
    }

    public void updateSettings(Merchant merchant, Instant now) {
        jdbc.sql("""
                UPDATE merchants SET name = :name, webhook_url = :webhookUrl, late_success_policy = :policy,
                                     payment_expiry_seconds = :expiry, status = :status, status_reason = :reason,
                                     updated_at = :now
                 WHERE id = :id
                """)
                .param("id", merchant.id())
                .param("name", merchant.name())
                .param("webhookUrl", merchant.webhookUrl())
                .param("policy", merchant.lateSuccessPolicy().name())
                .param("expiry", (int) merchant.paymentExpiry().toSeconds())
                .param("status", merchant.status().name())
                .param("reason", merchant.statusReason())
                .param("now", Sql.ts(now))
                .update();
    }

    public Optional<WebhookSecrets> findWebhookSecrets(String merchantId) {
        return jdbc.sql("""
                SELECT webhook_secret_enc, previous_webhook_secret_enc, previous_webhook_secret_expires_at
                  FROM merchants WHERE id = :id AND webhook_secret_enc IS NOT NULL
                """)
                .param("id", merchantId)
                .query((rs, n) -> new WebhookSecrets(rs.getBytes("webhook_secret_enc"),
                        rs.getBytes("previous_webhook_secret_enc"), Sql.instant(rs, "previous_webhook_secret_expires_at")))
                .optional();
    }

    public void replaceWebhookSecret(String merchantId, byte[] current, byte[] previous, Instant previousExpiresAt,
                                     Instant now) {
        jdbc.sql("""
                UPDATE merchants SET webhook_secret_enc = :current, previous_webhook_secret_enc = :previous,
                                     previous_webhook_secret_expires_at = :expiresAt, updated_at = :now
                 WHERE id = :id
                """)
                .param("id", merchantId)
                .param("current", current)
                .param("previous", previous)
                .param("expiresAt", Sql.ts(previousExpiresAt))
                .param("now", Sql.ts(now))
                .update();
    }

    // ---------------------------------------------------------------- API keys

    public void insertApiKey(String id, String merchantId, byte[] keyHash, String hint, String mode, Instant now) {
        jdbc.sql("""
                INSERT INTO api_keys (id, merchant_id, key_hash, key_hint, mode, status, created_at)
                VALUES (:id, :merchantId, :hash, :hint, :mode, 'ACTIVE', :now)
                """)
                .param("id", id)
                .param("merchantId", merchantId)
                .param("hash", keyHash)
                .param("hint", hint)
                .param("mode", mode)
                .param("now", Sql.ts(now))
                .update();
    }

    public Optional<ApiKeyMatch> findPrincipalByKeyHash(byte[] keyHash) {
        return jdbc.sql("""
                SELECT k.id AS key_id, k.merchant_id, k.last_used_at, m.rate_limit_read_per_second,
                       m.rate_limit_read_burst, m.rate_limit_write_per_second, m.rate_limit_write_burst
                  FROM api_keys k JOIN merchants m ON m.id = k.merchant_id
                 WHERE k.key_hash = :hash AND k.status = 'ACTIVE' AND m.status = 'ACTIVE'
                """)
                .param("hash", keyHash)
                .query((rs, n) -> new ApiKeyMatch(new MerchantPrincipal(rs.getString("merchant_id"), rs.getString("key_id"),
                        limit(rs, "read"), limit(rs, "write")), Sql.instant(rs, "last_used_at")))
                .optional();
    }

    public record RateLimitOverrides(RateLimit read, RateLimit write) {
    }

    public Optional<RateLimitOverrides> findRateLimits(String merchantId) {
        return jdbc.sql("""
                SELECT rate_limit_read_per_second, rate_limit_read_burst, rate_limit_write_per_second,
                       rate_limit_write_burst
                  FROM merchants WHERE id = :id
                """)
                .param("id", merchantId)
                .query((rs, n) -> new RateLimitOverrides(limit(rs, "read"), limit(rs, "write")))
                .optional();
    }

    public void updateRateLimits(String merchantId, RateLimitOverrides limits, Instant now) {
        jdbc.sql("""
                UPDATE merchants SET rate_limit_read_per_second = :readRate, rate_limit_read_burst = :readBurst,
                                     rate_limit_write_per_second = :writeRate, rate_limit_write_burst = :writeBurst,
                                     updated_at = :now
                 WHERE id = :id
                """)
                .param("id", merchantId)
                .param("readRate", limits.read() == null ? null : limits.read().perSecond())
                .param("readBurst", limits.read() == null ? null : limits.read().burst())
                .param("writeRate", limits.write() == null ? null : limits.write().perSecond())
                .param("writeBurst", limits.write() == null ? null : limits.write().burst())
                .param("now", Sql.ts(now))
                .update();
    }

    private static RateLimit limit(java.sql.ResultSet rs, String operation) throws java.sql.SQLException {
        double perSecond = rs.getDouble("rate_limit_" + operation + "_per_second");
        return rs.wasNull() ? null : new RateLimit(perSecond, rs.getInt("rate_limit_" + operation + "_burst"));
    }

    public void touchApiKey(String keyId, Instant now) {
        jdbc.sql("UPDATE api_keys SET last_used_at = :now WHERE id = :id")
                .param("id", keyId)
                .param("now", Sql.ts(now))
                .update();
    }

    public List<ApiKeyRow> findApiKeys(String merchantId) {
        return jdbc.sql("""
                SELECT id, key_hint, mode, status, created_at, last_used_at, revoked_at
                  FROM api_keys WHERE merchant_id = :merchantId ORDER BY created_at, id
                """)
                .param("merchantId", merchantId)
                .query((rs, n) -> new ApiKeyRow(rs.getString("id"), rs.getString("key_hint"), rs.getString("mode"),
                        rs.getString("status"), Sql.instant(rs, "created_at"), Sql.instant(rs, "last_used_at"),
                        Sql.instant(rs, "revoked_at")))
                .list();
    }

    public boolean revokeApiKey(String merchantId, String keyId, Instant now) {
        return jdbc.sql("""
                UPDATE api_keys SET status = 'REVOKED', revoked_at = :now
                 WHERE id = :id AND merchant_id = :merchantId AND status = 'ACTIVE'
                """)
                .param("id", keyId)
                .param("merchantId", merchantId)
                .param("now", Sql.ts(now))
                .update() == 1;
    }

    // ---------------------------------------------------------------- PSP accounts

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

    public Optional<ProviderAccountRow> findProviderAccount(String merchantId, String providerCode) {
        return jdbc.sql("SELECT * FROM merchant_provider_accounts WHERE merchant_id = :merchantId AND provider_code = :provider")
                .param("merchantId", merchantId)
                .param("provider", providerCode)
                .query(MerchantRepository::mapProviderAccount)
                .optional();
    }

    public Optional<ProviderAccountRow> findProviderAccountById(String id) {
        return jdbc.sql("SELECT * FROM merchant_provider_accounts WHERE id = :id")
                .param("id", id)
                .query(MerchantRepository::mapProviderAccount)
                .optional();
    }

    public List<ProviderAccountRow> findProviderAccounts(String merchantId) {
        return jdbc.sql("SELECT * FROM merchant_provider_accounts WHERE merchant_id = :merchantId ORDER BY provider_code")
                .param("merchantId", merchantId)
                .query(MerchantRepository::mapProviderAccount)
                .list();
    }

    public void updateProviderAccount(String id, String status, byte[] credentialsEncrypted,
                                      Instant credentialsUpdatedAt, Instant disabledAt) {
        jdbc.sql("""
                UPDATE merchant_provider_accounts
                   SET status = :status, credentials_enc = :credentials, credentials_updated_at = :credentialsUpdatedAt,
                       disabled_at = :disabledAt
                 WHERE id = :id
                """)
                .param("id", id)
                .param("status", status)
                .param("credentials", credentialsEncrypted)
                .param("credentialsUpdatedAt", Sql.ts(credentialsUpdatedAt))
                .param("disabledAt", Sql.ts(disabledAt))
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

    /** Active accounts plus accounts disabled since {@code disabledSince}: their settlements still arrive. */
    public List<MerchantDirectory.ProviderAccount> findAccountsToReconcile(Instant disabledSince) {
        return jdbc.sql("""
                SELECT merchant_id, provider_code FROM merchant_provider_accounts
                 WHERE status = 'ACTIVE' OR disabled_at >= :since
                 ORDER BY merchant_id, provider_code
                """)
                .param("since", Sql.ts(disabledSince))
                .query((rs, n) -> new MerchantDirectory.ProviderAccount(rs.getString("merchant_id"), rs.getString("provider_code")))
                .list();
    }

    private static Merchant mapMerchant(ResultSet rs, int rowNum) throws SQLException {
        return new Merchant(
                rs.getString("id"),
                rs.getString("name"),
                Merchant.Status.valueOf(rs.getString("status")),
                rs.getString("status_reason"),
                rs.getString("webhook_url"),
                Merchant.LateSuccessPolicy.valueOf(rs.getString("late_success_policy")),
                Duration.ofSeconds(rs.getInt("payment_expiry_seconds")),
                Sql.instant(rs, "created_at"));
    }

    private static ProviderAccountRow mapProviderAccount(ResultSet rs, int rowNum) throws SQLException {
        return new ProviderAccountRow(rs.getString("id"), rs.getString("merchant_id"), rs.getString("provider_code"),
                rs.getString("status"), rs.getBytes("credentials_enc"), Sql.instant(rs, "credentials_updated_at"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "disabled_at"));
    }
}
