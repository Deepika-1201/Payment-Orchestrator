package com.payments.gateway.merchant;

import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.support.IntegrationTest;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** Data key rotation (ADR-025): after re-encryption the old key can be removed without losing any secret. */
class DataKeyRotationIntegrationTest extends IntegrationTest {

    private static final byte[] TEST_KEY = Base64.getDecoder().decode("bG9jYWwtZGV2LWtleS0wMDAwMDAwMDAwMDAwMDAwMDA=");
    private static final byte[] NEW_KEY = new byte[32];

    static {
        new java.security.SecureRandom().nextBytes(NEW_KEY);
    }

    @Autowired
    private MerchantRepository repository;
    @Autowired
    private AuditLogger audit;

    @Test
    void everySecretMovesToTheNewPrimaryKeyAndTheOldKeyCanBeRetired() {
        TestMerchant merchant = createMerchant(ALPHA);
        Response linked = admin("PUT", "/admin/v1/merchants/" + merchant.id() + "/provider-accounts/" + ALPHA,
                Map.of("credentials", Map.of("api_key", "key_live_rotation_0001", "webhook_secret", "whsec_account_rotation")));
        assertThat(linked.status()).as(linked.raw()).isEqualTo(200);
        String accountId = str(linked.body(), "id");
        assertThat(str(admin("GET", "/admin/v1/security/data-keys", null).body(), "primary_key_id")).isEqualTo("legacy");

        SecretCipher rotated = new SecretCipher(Map.of(SecretCipher.LEGACY_KEY_ID, TEST_KEY, "k2026-10", NEW_KEY), "k2026-10");
        SecretRotationService rotation = new SecretRotationService(repository, rotated, audit);
        assertThat(rotation.usage().complete()).isFalse();

        SecretRotationService.RotationResult result = rotation.reEncryptAll("admin-token-0");

        assertThat(result.reEncrypted()).isEqualTo(2);
        assertThat(result.usage().complete()).isTrue();
        assertThat(result.usage().ciphertextsByKey()).containsOnlyKeys("k2026-10");
        SecretCipher newKeyOnly = new SecretCipher(Map.of("k2026-10", NEW_KEY), "k2026-10");
        byte[] webhookSecret = jdbc.sql("SELECT webhook_secret_enc FROM merchants WHERE id = ?").param(1, merchant.id())
                .query(byte[].class).single();
        assertThat(newKeyOnly.decrypt(webhookSecret)).isEqualTo(merchant.webhookSecret());
        byte[] credentials = jdbc.sql("SELECT credentials_enc FROM merchant_provider_accounts WHERE id = ?").param(1, accountId)
                .query(byte[].class).single();
        assertThat(newKeyOnly.decrypt(credentials, ProviderAccountService.context(accountId))).contains("key_live_rotation_0001");
        assertThat(rotation.reEncryptAll("admin-token-0").reEncrypted()).as("idempotent").isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'data_keys.re_encrypted'")).isEqualTo(2);
    }

    @Test
    void onlyAdminsMayReEncrypt() {
        assertThat(send("POST", "/admin/v1/security/data-keys/re-encrypt",
                Map.of("Authorization", "Bearer test-finance-token"), null).status()).isEqualTo(403);
        Response run = admin("POST", "/admin/v1/security/data-keys/re-encrypt", null);
        assertThat(run.status()).isEqualTo(200);
        assertThat(run.body().get("re_encrypted")).isEqualTo(0);
    }
}
