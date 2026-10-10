package com.payments.gateway.payment;

import com.payments.gateway.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyIntegrationTest extends IntegrationTest {

    private static Map<String, Object> body(String orderId) {
        return Map.of("amount", 10_000, "currency", "INR", "merchant_order_id", orderId);
    }

    @Test
    void sameKeyReplaysTheOriginalResponse() {
        TestMerchant merchant = createMerchant(ALPHA);

        Response first = post(merchant, "/v1/payments", "key-1", body("order_1"));
        Response second = post(merchant, "/v1/payments", "key-1", body("order_1"));

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(str(second.body(), "id")).isEqualTo(str(first.body(), "id"));
        assertThat(first.headers().firstValue("Idempotent-Replayed")).contains("false");
        assertThat(second.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }

    @Test
    void reusingKeyWithDifferentRequestIsRejected() {
        TestMerchant merchant = createMerchant(ALPHA);
        post(merchant, "/v1/payments", "key-1", body("order_1"));

        Response reused = post(merchant, "/v1/payments", "key-1", body("order_2"));

        assertThat(reused.status()).isEqualTo(422);
        assertThat(str(reused.body(), "code")).isEqualTo("idempotency_key_reuse");
    }

    @Test
    void keysAreScopedPerMerchant() {
        TestMerchant first = createMerchant(ALPHA);
        TestMerchant second = createMerchant(ALPHA);

        assertThat(post(first, "/v1/payments", "shared-key", body("order_1")).status()).isEqualTo(201);
        assertThat(post(second, "/v1/payments", "shared-key", body("order_1")).status()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(2);
    }

    @Test
    void mutatingRequestsRequireAKey() {
        TestMerchant merchant = createMerchant(ALPHA);

        Response response = post(merchant, "/v1/payments", null, body("order_1"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(str(response.body(), "code")).isEqualTo("idempotency_key_required");
    }

    @Test
    void businessErrorsAreReplayedToo() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");

        Response first = post(merchant, "/v1/payments/" + paymentId + "/capture", "cap-1", Map.of());
        Response replay = post(merchant, "/v1/payments/" + paymentId + "/capture", "cap-1", Map.of());

        assertThat(first.status()).isEqualTo(409);
        assertThat(replay.status()).isEqualTo(409);
        assertThat(str(replay.body(), "code")).isEqualTo("payment_invalid_state");
    }

    @Test
    void concurrentDuplicatesCreateExactlyOnePayment() throws Exception {
        TestMerchant merchant = createMerchant(ALPHA);
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> post(merchant, "/v1/payments", "concurrent-key", body("order_1")));
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (Future<Response> future : executor.invokeAll(calls)) {
                statuses.add(future.get().status());
            }
        }

        assertThat(statuses).allMatch(status -> status == 201 || status == 409);
        assertThat(statuses).contains(201);
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }

    @Test
    void validationErrorsListFieldsInSnakeCase() {
        TestMerchant merchant = createMerchant(ALPHA);

        Response response = post(merchant, "/v1/payments", "bad-1", Map.of("amount", 0, "currency", "inr"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(str(response.body(), "code")).isEqualTo("validation_error");
        assertThat(response.body().toString()).contains("merchant_order_id").contains("amount").contains("currency");
        assertThat(str(response.body(), "request_id")).startsWith("req_");
    }
}
