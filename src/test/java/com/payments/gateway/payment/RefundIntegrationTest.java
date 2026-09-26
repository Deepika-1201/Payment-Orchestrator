package com.payments.gateway.payment;

import com.payments.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

class RefundIntegrationTest extends IntegrationTest {

    private String succeededPayment(TestMerchant merchant, long amount) {
        String paymentId = str(createPayment(merchant, amount, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, upi("intent"));
        simulate(ALPHA, str(confirmed.body(), "latest_attempt.provider_reference"), "success", false);
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
        return paymentId;
    }

    private Response refund(TestMerchant merchant, String paymentId, Long amount) {
        Map<String, Object> body = amount == null ? Map.of() : Map.of("amount", amount, "reason", "customer request");
        return post(merchant, "/v1/payments/" + paymentId + "/refunds", UUID.randomUUID().toString(), body);
    }

    @Test
    void multiplePartialRefundsCannotExceedCapturedAmount() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = succeededPayment(merchant, 100_000);

        Response first = refund(merchant, paymentId, 30_000L);
        Response second = refund(merchant, paymentId, 50_000L);
        Response tooMuch = refund(merchant, paymentId, 30_000L);

        assertThat(first.status()).isEqualTo(201);
        assertThat(str(first.body(), "status")).isEqualTo("succeeded");
        assertThat(second.status()).isEqualTo(201);
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(str(tooMuch.body(), "code")).isEqualTo("amount_exceeds_refundable");
        assertThat(num(getPayment(merchant, paymentId), "amount_refunded")).isEqualTo(80_000);

        Response remainder = refund(merchant, paymentId, null);
        assertThat(num(remainder.body(), "amount")).isEqualTo(20_000);
        assertThat(num(getPayment(merchant, paymentId), "amount_refunded")).isEqualTo(100_000);
    }

    @Test
    void concurrentRefundsNeverOverRefund() throws Exception {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = succeededPayment(merchant, 100_000);
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> refund(merchant, paymentId, 40_000L));
        }
        int created = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            for (Future<Response> future : executor.invokeAll(calls)) {
                int status = future.get().status();
                assertThat(status).isIn(201, 422);
                if (status == 201) {
                    created++;
                }
            }
        }

        assertThat(created).isEqualTo(2);
        assertThat(count("SELECT COALESCE(SUM(amount), 0) FROM refunds WHERE payment_id = ? AND status <> 'FAILED'", paymentId))
                .isEqualTo(80_000);
    }

    @Test
    void refundsRequireASucceededPayment() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");

        Response response = refund(merchant, paymentId, 1_000L);

        assertThat(response.status()).isEqualTo(409);
        assertThat(str(response.body(), "code")).isEqualTo("payment_invalid_state");
    }

    @Test
    void pendingRefundIsResolvedByStatusCheck() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = succeededPayment(merchant, 100_000);

        Response pending = refund(merchant, paymentId, 1_007L);
        assertThat(str(pending.body(), "status")).isEqualTo("pending");

        resolveStatuses(Duration.ofSeconds(6), 1);

        assertThat(str(get(merchant, "/v1/refunds/" + str(pending.body(), "id")).body(), "status")).isEqualTo("succeeded");
        assertThat(num(getPayment(merchant, paymentId), "amount_refunded")).isEqualTo(1_007);
    }

    @Test
    void refundTimeoutIsUnknownUntilStatusCheckConfirms() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = succeededPayment(merchant, 100_000);

        Response unknown = refund(merchant, paymentId, 2_008L);
        assertThat(str(unknown.body(), "status")).isEqualTo("unknown");

        resolveStatuses(Duration.ofSeconds(6), 1);

        assertThat(str(get(merchant, "/v1/refunds/" + str(unknown.body(), "id")).body(), "status")).isEqualTo("succeeded");
    }

    @Test
    void failedRefundReleasesTheRefundableAmountAndNotifiesMerchant() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = succeededPayment(merchant, 100_000);

        Response failed = refund(merchant, paymentId, 3_009L);

        assertThat(str(failed.body(), "status")).isEqualTo("failed");
        assertThat(count("SELECT count(*) FROM merchant_events WHERE type = 'refund.failed'")).isEqualTo(1);
        assertThat(refund(merchant, paymentId, 100_000L).status()).isEqualTo(201);
    }

    @Test
    void merchantRefundIdIsUniquePerMerchant() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = succeededPayment(merchant, 100_000);
        Map<String, Object> body = Map.of("amount", 1_000, "merchant_refund_id", "rf_1");

        assertThat(post(merchant, "/v1/payments/" + paymentId + "/refunds", "k1", body).status()).isEqualTo(201);
        Response duplicate = post(merchant, "/v1/payments/" + paymentId + "/refunds", "k2", body);

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(str(duplicate.body(), "code")).isEqualTo("refund_already_exists");
    }
}
