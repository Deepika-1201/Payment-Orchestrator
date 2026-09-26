package com.payments.gateway;

import com.payments.gateway.support.FakeMerchantEndpoint;
import com.payments.gateway.support.IntegrationTest;
import com.payments.gateway.support.OpenApiContract;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** docs/openapi.yaml is the merchant API contract: endpoints, statuses, headers and bodies must match it exactly. */
class ApiContractTest extends IntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void theContractDocumentsExactlyTheMerchantEndpoints() {
        Set<String> implemented = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream()
                .filter(pattern -> pattern.startsWith("/v1/") && !pattern.startsWith("/v1/webhooks/"))
                .forEach(pattern -> info.getMethodsCondition().getMethods()
                        .forEach(method -> implemented.add(method.name() + " " + OpenApiContract.normalize(pattern)))));

        assertThat(implemented).isNotEmpty();
        assertThat(OpenApiContract.get().operations()).containsExactlyElementsOf(implemented);
    }

    @Test
    void paymentLifecycleResponsesAndWebhooksMatchTheContract() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            TestMerchant merchant = createMerchantWith(endpoint.url(), null, ALPHA);

            Map<String, Object> create = new LinkedHashMap<>();
            create.put("amount", 49_900);
            create.put("currency", "INR");
            create.put("merchant_order_id", "order_contract_1");
            create.put("description", "Contract test order");
            create.put("customer", Map.of("reference", "cust_42", "email", "buyer@example.com", "phone", "+919999999999"));
            create.put("metadata", Map.of("channel", "web"));
            create.put("expires_in_seconds", 900);
            String createKey = key();
            Response created = assertContract("POST", "/v1/payments", create,
                    post(merchant, "/v1/payments", createKey, create));
            assertThat(created.status()).isEqualTo(201);
            Response replayed = assertContract("POST", "/v1/payments", create,
                    post(merchant, "/v1/payments", createKey, create));
            assertThat(replayed.headers().firstValue("Idempotent-Replayed")).contains("true");
            String paymentId = str(created.body(), "id");

            Response session = assertContract("POST", "/v1/checkout-sessions",
                    Map.of("payment_id", paymentId, "return_url", "https://merchant.example/orders/1"),
                    post(merchant, "/v1/checkout-sessions", key(),
                            Map.of("payment_id", paymentId, "return_url", "https://merchant.example/orders/1")));
            assertThat(session.status()).isEqualTo(201);

            Map<String, Object> confirm = Map.of("payment_method", upi("intent"),
                    "return_url", "https://merchant.example/orders/1",
                    "client", Map.of("ip", "203.0.113.7", "user_agent", "Mozilla/5.0", "device_id", "dev-1"));
            Response confirmed = assertContract("POST", "/v1/payments/{payment_id}/confirm", confirm,
                    post(merchant, "/v1/payments/" + paymentId + "/confirm", key(), confirm));
            assertThat(str(confirmed.body(), "next_action.type")).isEqualTo("upi_intent");
            assertThat(assertContract("POST", "/v1/payments/{payment_id}/confirm", confirm,
                    post(merchant, "/v1/payments/" + paymentId + "/confirm", key(), confirm)).status()).isEqualTo(409);

            simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
            Response succeeded = assertContract("GET", "/v1/payments/{payment_id}", null,
                    get(merchant, "/v1/payments/" + paymentId));
            assertThat(str(succeeded.body(), "status")).isEqualTo("succeeded");

            Map<String, Object> refund = Map.of("amount", 10_000, "reason", "damaged item", "merchant_refund_id", "rf_1");
            Response refunded = assertContract("POST", "/v1/payments/{payment_id}/refunds", refund,
                    post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), refund));
            assertThat(refunded.status()).isEqualTo(201);
            assertThat(assertContract("POST", "/v1/payments/{payment_id}/refunds", Map.of("amount", 1_000_000),
                    post(merchant, "/v1/payments/" + paymentId + "/refunds", key(), Map.of("amount", 1_000_000)))
                    .status()).isEqualTo(422);
            assertContract("GET", "/v1/payments/{payment_id}/refunds", null,
                    get(merchant, "/v1/payments/" + paymentId + "/refunds"));
            assertContract("GET", "/v1/refunds/{refund_id}", null,
                    get(merchant, "/v1/refunds/" + str(refunded.body(), "id")));

            deliveryWorker.deliverDue();
            assertThat(endpoint.received()).isNotEmpty();
            endpoint.received().forEach(delivery -> OpenApiContract.get().assertSchema("Event", delivery.body()));
        }
    }

    @Test
    void manualCaptureAndCancelResponsesMatchTheContract() {
        TestMerchant merchant = createMerchant(ALPHA);
        String captureId = str(createPayment(merchant, 250_000, "manual"), "id");
        Response confirmed = confirm(merchant, captureId, card());
        assertThat(str(confirmed.body(), "next_action.type")).isEqualTo("redirect");
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        Map<String, Object> capture = Map.of("amount", 250_000);
        Response captured = assertContract("POST", "/v1/payments/{payment_id}/capture", capture,
                post(merchant, "/v1/payments/" + captureId + "/capture", key(), capture));
        assertThat(str(captured.body(), "status")).isEqualTo("succeeded");

        String cancelId = str(createPayment(merchant, 150_000, "manual"), "id");
        Map<String, Object> cancel = Map.of("reason", "requested_by_customer");
        Response cancelled = assertContract("POST", "/v1/payments/{payment_id}/cancel", cancel,
                post(merchant, "/v1/payments/" + cancelId + "/cancel", key(), cancel));
        assertThat(str(cancelled.body(), "status")).isEqualTo("cancelled");
    }

    @Test
    void errorResponsesMatchTheContract() {
        TestMerchant merchant = createMerchant(ALPHA);
        String createKey = key();
        Map<String, Object> create = Map.of("amount", 10_000, "currency", "INR", "merchant_order_id", "order_errors");
        post(merchant, "/v1/payments", createKey, create);

        Response invalid = assertContract("POST", "/v1/payments", null,
                post(merchant, "/v1/payments", key(), Map.of("amount", 1, "currency", "inr")));
        assertThat(str(invalid.body(), "code")).isEqualTo("validation_error");
        assertThat(invalid.body()).containsKey("errors");
        Response missingKey = assertContract("POST", "/v1/payments", null, post(merchant, "/v1/payments", null, create));
        assertThat(str(missingKey.body(), "code")).isEqualTo("idempotency_key_required");
        Response malformed = assertContract("POST", "/v1/payments", null, post(merchant, "/v1/payments", key(), "{not json"));
        assertThat(str(malformed.body(), "code")).isEqualTo("malformed_request");
        Response reused = assertContract("POST", "/v1/payments", null, post(merchant, "/v1/payments", createKey,
                Map.of("amount", 20_000, "currency", "INR", "merchant_order_id", "order_errors")));
        assertThat(str(reused.body(), "code")).isEqualTo("idempotency_key_reuse");
        Response unsupported = assertContract("POST", "/v1/payments", null, post(merchant, "/v1/payments", key(),
                Map.of("amount", 10_000, "currency", "USD", "merchant_order_id", "order_usd")));
        assertThat(str(unsupported.body(), "code")).isEqualTo("unsupported_currency");

        Response anonymous = assertContract("GET", "/v1/payments/{payment_id}", null,
                send("GET", "/v1/payments/pay_x", Map.of(), null));
        assertThat(str(anonymous.body(), "code")).isEqualTo("authentication_required");
        Response badKey = assertContract("GET", "/v1/payments/{payment_id}", null,
                send("GET", "/v1/payments/pay_x", Map.of("Authorization", "Bearer sk_test_nope"), null));
        assertThat(str(badKey.body(), "code")).isEqualTo("invalid_api_key");
        Response missing = assertContract("GET", "/v1/payments/{payment_id}", null, get(merchant, "/v1/payments/pay_missing"));
        assertThat(str(missing.body(), "code")).isEqualTo("resource_not_found");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
