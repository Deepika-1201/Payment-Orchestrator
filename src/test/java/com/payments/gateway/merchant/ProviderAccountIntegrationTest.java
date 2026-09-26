package com.payments.gateway.merchant;

import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** Merchants' own PSP accounts: credential handling, rejected credentials, disabling, and tenant isolation. */
class ProviderAccountIntegrationTest extends IntegrationTest {

    private static final String API_KEY = "key_live_1234567890abcd";
    private static final String ACCOUNT_SECRET = "whsec_account_only_5678";

    @Test
    void credentialsAreEncryptedMaskedAndUsedToVerifyTheAccountsWebhooks() {
        TestMerchant merchant = createMerchant(ALPHA);

        Response linked = link(merchant, ALPHA, Map.of("api_key", API_KEY, "webhook_secret", ACCOUNT_SECRET));

        assertThat(linked.status()).isEqualTo(200);
        String accountId = str(linked.body(), "id");
        assertThat(str(linked.body(), "credentials.api_key")).isEqualTo("\u2026abcd");
        assertThat(str(linked.body(), "credentials.webhook_secret")).isEqualTo("\u2026" + "5678");
        assertThat(str(linked.body(), "webhook_path")).isEqualTo("/v1/webhooks/providers/" + ALPHA + "/" + accountId);
        assertThat(linked.raw()).doesNotContain(API_KEY).doesNotContain(ACCOUNT_SECRET);
        String stored = jdbc.sql("SELECT encode(credentials_enc, 'escape') FROM merchant_provider_accounts WHERE id = ?")
                .param(1, accountId).query(String.class).single();
        assertThat(stored).doesNotContain(API_KEY).doesNotContain(ACCOUNT_SECRET);
        assertThat(jdbc.sql("SELECT details::text FROM audit_log WHERE resource_id = ?").param(1, accountId)
                .query(String.class).list()).singleElement().asString().contains("api_key").doesNotContain("1234567890");

        payAndSucceed(merchant, 10_000);

        String body = json.write(Map.of("event_id", "evt_platform_signed", "type", "payment.updated", "status", "captured"));
        Response platformSigned = send("POST", "/v1/webhooks/providers/" + ALPHA + "/" + accountId,
                Map.of(MockPaymentProvider.SIGNATURE_HEADER, alpha().sign(clock.instant().getEpochSecond(), body)), body);
        assertThat(platformSigned.status()).isEqualTo(401);
        assertThat(link(merchant, ALPHA, Map.of("api_secret", "x")).status()).isEqualTo(400);
        assertThat(link(merchant, ALPHA, Map.of("api_key", " ")).status()).isEqualTo(400);
    }

    @Test
    void rejectedCredentialsFailOverWithoutOpeningTheCircuitSharedByAllMerchants() {
        TestMerchant misconfigured = createMerchant(ALPHA, BETA);
        link(misconfigured, ALPHA, Map.of("api_key", "bad_key_0000000000"));

        for (int i = 0; i < 12; i++) {
            Response confirmed = confirm(misconfigured, str(createPayment(misconfigured, 10_000, "automatic"), "id"), upi("intent"));
            assertThat(str(confirmed.body(), "latest_attempt.provider")).isEqualTo(BETA);
        }

        assertThat(jdbc.sql("SELECT DISTINCT failure_code FROM payment_attempts WHERE merchant_id = ? AND provider_code = ?")
                .param(1, misconfigured.id()).param(2, ALPHA).query(String.class).list())
                .containsExactly("provider_credentials_rejected");
        assertThat(count("SELECT count(*) FROM payment_attempts WHERE merchant_id = ? AND provider_code = ?",
                misconfigured.id(), ALPHA)).isEqualTo(12);
        assertThat(providerClient.circuitState(ALPHA)).isEqualTo("CLOSED");
        TestMerchant healthy = createMerchant(ALPHA);
        Response confirmed = confirm(healthy, str(createPayment(healthy, 10_000, "automatic"), "id"), upi("intent"));
        assertThat(str(confirmed.body(), "latest_attempt.provider")).isEqualTo(ALPHA);
    }

    @Test
    void aDisabledAccountTakesNoNewPaymentsButFinishesItsOwn() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);
        Map<String, Object> onAlpha = payAndSucceed(merchant, 10_000);
        assertThat(str(onAlpha, "latest_attempt.provider")).isEqualTo(ALPHA);

        Response disabled = admin("POST", "/admin/v1/merchants/" + merchant.id() + "/provider-accounts/" + ALPHA + "/disable", null);

        assertThat(str(disabled.body(), "status")).isEqualTo("disabled");
        assertThat(disabled.body()).containsKey("disabled_at");
        assertThat(admin("GET", "/admin/v1/merchants/" + merchant.id(), null).body().get("providers")).isEqualTo(List.of(BETA));
        Response confirmed = confirm(merchant, str(createPayment(merchant, 10_000, "automatic"), "id"), upi("intent"));
        assertThat(str(confirmed.body(), "latest_attempt.provider")).isEqualTo(BETA);
        Response refund = post(merchant, "/v1/payments/" + str(onAlpha, "id") + "/refunds", UUID.randomUUID().toString(),
                Map.of("amount", 5_000));
        assertThat(refund.status()).isEqualTo(201);
        assertThat(str(refund.body(), "provider")).isEqualTo(ALPHA);
        assertThat(str(refund.body(), "status")).isEqualTo("succeeded");

        Response enabled = link(merchant, ALPHA, null);
        assertThat(str(enabled.body(), "status")).isEqualTo("active");
        assertThat(enabled.body()).doesNotContainKey("disabled_at");
    }

    @Test
    void anAccountsWebhooksCanNeitherTouchAnotherMerchantsPaymentsNorClaimItsEventIds() {
        TestMerchant victim = createMerchant(ALPHA);
        String victimAccount = str(link(victim, ALPHA, Map.of("webhook_secret", "whsec_victim_account_0001")).body(), "id");
        TestMerchant attacker = createMerchant(ALPHA);
        String attackerAccount = str(link(attacker, ALPHA, Map.of("webhook_secret", "whsec_attacker_account_1")).body(), "id");
        String paymentId = str(createPayment(victim, 10_000, "automatic"), "id");
        Response confirmed = confirm(victim, paymentId, upi("intent"));
        Map<String, Object> event = Map.of("event_id", "evt_contested", "type", "payment.updated",
                "provider_reference", str(confirmed.body(), "latest_attempt.provider_reference"),
                "merchant_reference", str(confirmed.body(), "latest_attempt.id"),
                "status", "captured", "amount", 10_000, "currency", "INR");

        Response forged = pspWebhook(attackerAccount, "whsec_attacker_account_1", event);

        assertThat(forged.status()).isEqualTo(200);
        assertThat(str(getPayment(victim, paymentId), "status")).isEqualTo("requires_action");
        assertThat(jdbc.sql("SELECT status FROM provider_webhook_events WHERE merchant_account_id = ?").param(1, attackerAccount)
                .query(String.class).list()).containsExactly("IGNORED");

        Response genuine = pspWebhook(victimAccount, "whsec_victim_account_0001", event);
        assertThat(str(genuine.body(), "received")).isEqualTo("1");
        assertThat(str(getPayment(victim, paymentId), "status")).isEqualTo("succeeded");
        assertThat(send("POST", "/v1/webhooks/providers/" + BETA + "/" + victimAccount, Map.of(), "{}").status()).isEqualTo(404);
        assertThat(send("POST", "/v1/webhooks/providers/" + ALPHA + "/mpa_unknown", Map.of(), "{}").status()).isEqualTo(404);
    }

    @Test
    void accountsAreListedPerMerchant() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);

        Response accounts = admin("GET", "/admin/v1/merchants/" + merchant.id() + "/provider-accounts", null);

        assertThat(list(accounts.body(), "data")).extracting(account -> str(account, "provider")).containsExactly(ALPHA, BETA);
        assertThat(list(accounts.body(), "data")).allSatisfy(account -> assertThat(str(account, "status")).isEqualTo("active"));
        assertThat(link(merchant, "NO_SUCH_PSP", Map.of()).status()).isEqualTo(404);
    }

    private Response link(TestMerchant merchant, String provider, Map<String, String> credentials) {
        return admin("PUT", "/admin/v1/merchants/" + merchant.id() + "/provider-accounts/" + provider,
                credentials == null ? null : Map.of("credentials", credentials));
    }

    private Response pspWebhook(String accountId, String secret, Map<String, Object> event) {
        String body = json.write(event);
        return send("POST", "/v1/webhooks/providers/" + ALPHA + "/" + accountId,
                Map.of(MockPaymentProvider.SIGNATURE_HEADER, MockPaymentProvider.sign(secret, clock.instant().getEpochSecond(), body)),
                body);
    }

    private MockPaymentProvider alpha() {
        return mockProviders.stream().filter(provider -> provider.code().equals(ALPHA)).findFirst().orElseThrow();
    }
}
