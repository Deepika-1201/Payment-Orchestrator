package com.payments.gateway;

import com.payments.gateway.shared.web.ApiSecurityFilter;
import com.payments.gateway.support.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** Response headers and request limits on the JSON APIs (ADR-022). */
class SecurityHardeningIntegrationTest extends IntegrationTest {

    private static final Map<String, Object> PAYMENT = Map.of("amount", 10_000, "currency", "INR", "merchant_order_id", "o1");

    @Test
    void apiResponsesAreNeverCachedSniffedOrFramedAndGetHstsOverHttps() {
        TestMerchant merchant = createMerchant(ALPHA);
        List<Response> responses = List.of(
                post(merchant, "/v1/payments", UUID.randomUUID().toString(), PAYMENT),
                get(merchant, "/v1/payments/pay_missing"),
                send("GET", "/v1/payments/pay_missing", Map.of(), null),
                admin("GET", "/admin/v1/reviews", null));

        assertThat(responses).extracting(Response::status).containsExactly(201, 404, 401, 200);
        for (Response response : responses) {
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
            assertThat(response.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(csp ->
                    assertThat(csp).contains("frame-ancestors 'none'"));
            assertThat(response.headers().firstValue("Strict-Transport-Security")).as("plain HTTP").isEmpty();
        }
        Response overTls = send("GET", "/v1/payments/pay_missing",
                Map.of("Authorization", "Bearer " + merchant.apiKey(), "X-Forwarded-Proto", "https"), null);
        assertThat(overTls.headers().firstValue("Strict-Transport-Security")).contains(ApiSecurityFilter.HSTS);
        assertThat(send("GET", "/checkout/cs_unknown", Map.of("X-Forwarded-Proto", "https"), null)
                .headers().firstValue("Strict-Transport-Security")).contains(ApiSecurityFilter.HSTS);
    }

    @Test
    void oversizedBodiesAreRefusedWhetherDeclaredOrChunked() throws Exception {
        TestMerchant merchant = createMerchant(ALPHA);
        String big = "{\"amount\":100,\"currency\":\"INR\",\"merchant_order_id\":\"o2\",\"description\":\"" + "x".repeat(300_000) + "\"}";

        Response declared = post(merchant, "/v1/payments", UUID.randomUUID().toString(), big);

        assertThat(declared.status()).isEqualTo(413);
        assertThat(str(declared.body(), "code")).isEqualTo("payload_too_large");
        HttpRequest chunked = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/payments"))
                .header("Authorization", "Bearer " + merchant.apiKey())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big.getBytes(StandardCharsets.UTF_8))))
                .build();
        HttpResponse<String> streamed = http.send(chunked, HttpResponse.BodyHandlers.ofString());
        assertThat(streamed.statusCode()).isEqualTo(413);
        assertThat(streamed.body()).contains("payload_too_large");
        assertThat(count("SELECT count(*) FROM payments")).isZero();
        assertThat(post(merchant, "/v1/payments", UUID.randomUUID().toString(), PAYMENT).status()).isEqualTo(201);
    }
}
