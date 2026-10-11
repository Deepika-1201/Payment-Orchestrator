package com.payments.gateway.payout.razorpay;

import com.payments.gateway.payout.spi.PayoutProvider;
import com.payments.gateway.payout.spi.PayoutProvider.Destination;
import com.payments.gateway.payout.spi.PayoutProvider.Instruction;
import com.payments.gateway.payout.spi.PayoutProvider.Outcome;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.support.StubPsp;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RazorpayPayoutProviderTest {
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());
    private final Instruction instruction = new Instruction("pout_our_reference", "mer_business", "7878780080316316", Money.of(10_000, "INR"),
            new Destination("Example Merchant", "123456789012", "HDFC0001234"));

    @Test
    void compositeCreateUsesFrozenSourceDestinationAndIdempotencyKey() {
        try (var stub = new StubPsp("/v1", 404, "{}")) {
            stub.on("POST /v1/payouts", 200, payload("processed", true));
            var provider = provider(stub);
            var result = provider.create(instruction);
            assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
            assertThat(result.amount()).isEqualTo(instruction.amount());
            assertThat(result.destination()).isEqualTo(instruction.destination());
            var request = stub.last("POST /v1/payouts");
            assertThat(request.header("X-Payout-Idempotency")).isEqualTo(instruction.id());
            assertThat(request.authorization()).startsWith("Basic ");
            var body = json.read(request.body(), tools.jackson.databind.JsonNode.class);
            assertThat(body.path("account_number").asString()).isEqualTo(instruction.sourceAccount());
            assertThat(body.path("reference_id").asString()).isEqualTo(instruction.id());
            assertThat(body.path("mode").asString()).isEqualTo("NEFT");
            assertThat(body.path("queue_if_low_balance").asBoolean()).isFalse();
            assertThat(body.path("fund_account").path("bank_account").path("account_number").asString()).isEqualTo("123456789012");
            assertThat(body.path("amount").asLong()).isEqualTo(10_000);
        }
    }

    @Test
    void fetchWithoutProviderIdUsesReferenceAndResolvesFundAccountWithoutACreate() {
        try (var stub = new StubPsp("/v1", 404, "{}")) {
            stub.on("GET /v1/payouts", 200, "{\"items\":[" + payload("processed", false) + "]}");
            stub.on("GET /v1/fund_accounts/fa_known", 200, account());
            var result = provider(stub).fetch(instruction, null);
            assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
            assertThat(result.destination()).isEqualTo(instruction.destination());
            assertThat(stub.last("GET /v1/payouts").query()).contains("account_number=7878780080316316", "reference_id=pout_our_reference");
            assertThat(stub.requests()).allMatch(request -> "GET".equals(request.method()));
        }
    }

    @Test
    void ambiguousFailuresAndMultipleMatchesNeverBecomeReleasable() {
        try (var stub = new StubPsp("/v1", 404, "{}")) {
            var provider = provider(stub);
            stub.on("GET /v1/payouts/provider_known", 200, payload("failed", true));
            assertThat(provider.fetch(instruction, "provider_known").outcome()).isEqualTo(Outcome.FAILURE_UNCONFIRMED);
            stub.on("GET /v1/payouts", 200, "{\"items\":[" + payload("processed", true) + "," + payload("processed", true) + "]}");
            assertThat(provider.fetch(instruction, null).outcome()).isEqualTo(Outcome.UNKNOWN);
            stub.on("GET /v1/payouts", 200, "{\"items\":[]}");
            assertThat(provider.fetch(instruction, null).outcome()).isEqualTo(Outcome.NOT_FOUND);
            stub.on("POST /v1/payouts", 500, "{}");
            assertThat(provider.create(instruction).outcome()).isEqualTo(Outcome.UNKNOWN);
            for (String state : List.of("rejected", "cancelled")) {
                stub.on("GET /v1/payouts/provider_known", 200, payload(state, true));
                assertThat(provider.fetch(instruction, "provider_known").outcome()).isEqualTo(Outcome.REJECTED);
            }
            stub.on("GET /v1/payouts/provider_known", 200, payload("reversed", true));
            assertThat(provider.fetch(instruction, "provider_known").outcome()).isEqualTo(Outcome.RETURNED);
        }
    }

    @Test
    void webhooksRequireSignatureAndCorrectProviderAccount() {
        try (var stub = new StubPsp("/v1", 404, "{}")) {
            var provider = provider(stub);
            String body = "{\"account_id\":\"acc_platform\",\"payload\":{\"payout\":{\"entity\":" + payload("reversed", true) + "}}}";
            assertThatThrownBy(() -> provider.parseWebhook(Map.of("X-Razorpay-Signature", "wrong"), body))
                    .isInstanceOf(PayoutProvider.InvalidWebhook.class);
            var event = provider.parseWebhook(Map.of("x-razorpay-signature", Hashing.hmacSha256Hex("webhook-secret", body)), body);
            assertThat(event.referenceId()).isEqualTo(instruction.id());
            assertThat(event.result().outcome()).isEqualTo(Outcome.RETURNED);
            assertThat(event.id()).hasSize(64);
            String other = body.replace("acc_platform", "acc_other");
            assertThatThrownBy(() -> provider.parseWebhook(Map.of("X-Razorpay-Signature", Hashing.hmacSha256Hex("webhook-secret", other)), other))
                    .isInstanceOf(PayoutProvider.InvalidWebhook.class);
        }
    }

    @Test
    void productionPinsTheApiAndCredentialModeAndValidatesProviderMinimum() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        var test = properties(URI.create("https://api.razorpay.com/v1"), "rzp_test_example");
        assertThatThrownBy(() -> new RazorpayPayoutProvider(test, json, production)).hasMessageContaining("credential mode");
        assertThatThrownBy(() -> new RazorpayPayoutProvider(properties(URI.create("https://api.razorpay.com.attacker.invalid/v1"), "rzp_live_example"),
                json, production)).hasMessageContaining("official HTTPS");
        var provider = new RazorpayPayoutProvider(properties(URI.create("https://api.razorpay.com/v1"), "rzp_live_example"), json, production);
        assertThatThrownBy(() -> provider.validate(new Instruction(instruction.id(), instruction.merchantId(), instruction.sourceAccount(),
                Money.of(99, "INR"), instruction.destination()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(test.toString()).doesNotContain("key-secret", "webhook-secret", "7878780080316316");
    }

    @Test
    void signedEvidenceAndLookupsRemainBoundToTheConfiguredSourceAndFundAccount() {
        try (var stub = new StubPsp("/v1", 404, "{}")) {
            var provider = provider(stub);
            var wrongSource = json.read(payload("processed", true), tools.jackson.databind.node.ObjectNode.class);
            wrongSource.put("debit_account_number", "999999999999");
            stub.on("GET /v1/payouts/provider_known", 200, json.write(wrongSource));
            assertThat(provider.fetch(instruction, "provider_known").outcome()).isEqualTo(Outcome.UNKNOWN);
            String body = json.write(Map.of("account_id", "acc_platform", "payload", Map.of("payout", Map.of("entity", wrongSource))));
            assertThatThrownBy(() -> provider.parseWebhook(Map.of("X-Razorpay-Signature", Hashing.hmacSha256Hex("webhook-secret", body)), body))
                    .isInstanceOf(PayoutProvider.InvalidWebhook.class).hasMessageContaining("source account");

            stub.on("GET /v1/payouts/provider_known", 200, payload("processed", false));
            var wrongAccount = json.read(account(), tools.jackson.databind.node.ObjectNode.class);
            wrongAccount.put("id", "fa_other");
            stub.on("GET /v1/fund_accounts/fa_known", 200, json.write(wrongAccount));
            assertThatThrownBy(() -> provider.fetch(instruction, "provider_known")).hasMessageContaining("destination reference mismatch");
            assertThat(stub.requests()).allMatch(request -> "GET".equals(request.method()));
        }
    }

    private RazorpayPayoutProvider provider(StubPsp stub) {
        return new RazorpayPayoutProvider(properties(stub.baseUrl(), "rzp_test_example"), json, new MockEnvironment());
    }

    private static RazorpayPayoutProperties properties(URI uri, String key) {
        return new RazorpayPayoutProperties(true, uri, key, "key-secret", "webhook-secret", "acc_platform", "7878780080316316",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private String payload(String status, boolean embedded) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("id", "provider_known");
        body.put("reference_id", instruction.id());
        body.put("amount", instruction.amount().amount());
        body.put("currency", "INR");
        body.put("status", status);
        body.put("fund_account_id", "fa_known");
        body.put("fees", 590);
        body.put("tax", 90);
        body.put("debit_account_number", instruction.sourceAccount());
        if (embedded) {
            body.put("fund_account", json.read(account(), tools.jackson.databind.JsonNode.class));
        }
        return json.write(body);
    }

    private String account() {
        return json.write(Map.of("id", "fa_known", "account_type", "bank_account", "bank_account", Map.of(
                "name", instruction.destination().beneficiary(), "account_number", instruction.destination().accountNumber(), "ifsc", instruction.destination().ifsc())));
    }
}