package com.payments.gateway.support;

import com.payments.gateway.payment.application.ExpiryJob;
import com.payments.gateway.payment.application.StatusResolver;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderHealthTracker;
import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.routing.RoutingRuleCache;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.webhook.inbound.ProviderWebhookService;
import com.payments.gateway.webhook.outbound.WebhookDeliveryWorker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full application on a random port against embedded PostgreSQL. Workers are disabled; tests drive jobs explicitly
 * and control time with {@link MutableClock}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {

    protected static final String ADMIN_TOKEN = "test-admin-token";
    protected static final String ALPHA = "MOCK_ALPHA";
    protected static final String BETA = "MOCK_BETA";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.now());
        }
    }

    public record Response(int status, Map<String, Object> body, HttpHeaders headers, String raw) {
    }

    public record TestMerchant(String id, String apiKey, String webhookSecret) {
    }

    @Value("${local.server.port}")
    protected int port;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected JsonCodec json;
    @Autowired
    protected ProviderClient providerClient;
    @Autowired
    protected ProviderHealthTracker health;
    @Autowired
    protected RoutingRuleCache routingRules;
    @Autowired
    protected List<MockPaymentProvider> mockProviders;
    @Autowired
    protected StatusResolver statusResolver;
    @Autowired
    protected ExpiryJob expiryJob;
    @Autowired
    protected WebhookDeliveryWorker deliveryWorker;
    @Autowired
    protected ProviderWebhookService inbox;

    protected final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void resetState() {
        jdbc.sql("""
                TRUNCATE merchants, api_keys, merchant_provider_accounts, payments, payment_attempts, refunds,
                         payment_transitions, idempotency_records, provider_webhook_events, merchant_events,
                         webhook_deliveries, routing_rules, audit_log, ledger_entries, ledger_transactions,
                         ledger_accounts, reconciliation_lines, reconciliation_exceptions, reconciliation_runs,
                         checkout_sessions, mandates, mandate_debits, mandate_transitions
                         RESTART IDENTITY CASCADE
                """).update();
        clock.set(Instant.now());
        providerClient.resetCircuits();
        health.reset();
        mockProviders.forEach(provider -> {
            provider.psp().setAvailable(true);
            provider.psp().clearAnomalies();
            provider.psp().resetFxRates();
        });
        routingRules.refresh();
    }

    // ------------------------------------------------------------------ HTTP

    protected Response send(String method, String path, Map<String, String> headers, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30));
        headers.forEach(builder::header);
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json.write(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = response.body() == null || response.body().isBlank() || !response.body().startsWith("{")
                    ? Map.of()
                    : json.read(response.body(), MAP);
            return new Response(response.statusCode(), parsed, response.headers(), response.body());
        } catch (IOException e) {
            throw new AssertionError("HTTP call failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    protected Response post(TestMerchant merchant, String path, String idempotencyKey, Object body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + merchant.apiKey());
        if (idempotencyKey != null) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        return send("POST", path, headers, body);
    }

    protected Response get(TestMerchant merchant, String path) {
        return send("GET", path, Map.of("Authorization", "Bearer " + merchant.apiKey()), null);
    }

    protected Response admin(String method, String path, Object body) {
        return send(method, path, Map.of("Authorization", "Bearer " + ADMIN_TOKEN), body);
    }

    /** Asserts that a request (if given) and its response match docs/openapi.yaml for the path template. */
    protected Response assertContract(String method, String pathTemplate, Object requestBody, Response response) {
        if (requestBody != null) {
            OpenApiContract.get().assertRequest(method, pathTemplate, json.write(requestBody));
        }
        OpenApiContract.get().assertResponse(method, pathTemplate, response.status(), response.headers(), response.raw());
        return response;
    }

    // ------------------------------------------------------------------ fixtures

    protected TestMerchant createMerchantWith(String webhookUrl, String lateSuccessPolicy, String... providers) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "Test Merchant");
        request.put("webhook_url", webhookUrl);
        request.put("late_success_policy", lateSuccessPolicy);
        request.put("providers", Arrays.asList(providers));
        Response created = admin("POST", "/admin/v1/merchants", request);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        String merchantId = str(created.body(), "id");
        Response key = admin("POST", "/admin/v1/merchants/" + merchantId + "/api-keys", null);
        assertThat(key.status()).isEqualTo(201);
        return new TestMerchant(merchantId, str(key.body(), "api_key"), str(created.body(), "webhook_secret"));
    }

    protected TestMerchant createMerchant(String... providers) {
        return createMerchantWith(null, null, providers);
    }

    protected Map<String, Object> createPayment(TestMerchant merchant, long amount, String captureMethod) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount", amount);
        request.put("currency", "INR");
        request.put("merchant_order_id", "order_" + UUID.randomUUID().toString().substring(0, 8));
        request.put("capture_method", captureMethod);
        request.put("customer", Map.of("reference", "cust_" + UUID.randomUUID().toString().substring(0, 8), "email", "buyer@example.com"));
        Response response = post(merchant, "/v1/payments", UUID.randomUUID().toString(), request);
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        return response.body();
    }

    protected Response confirm(TestMerchant merchant, String paymentId, Map<String, Object> paymentMethod) {
        return post(merchant, "/v1/payments/" + paymentId + "/confirm", UUID.randomUUID().toString(),
                Map.of("payment_method", paymentMethod, "return_url", "https://merchant.example/return"));
    }

    protected static Map<String, Object> upi(String flow) {
        return Map.of("type", "upi", "upi", Map.of("flow", flow));
    }

    protected static Map<String, Object> card() {
        return Map.of("type", "card");
    }

    protected static Map<String, Object> netbanking(String bankCode) {
        return Map.of("type", "netbanking", "netbanking", Map.of("bank_code", bankCode));
    }

    protected Response simulate(String provider, String providerReference, String outcome, boolean duplicateWebhook) {
        return send("POST", "/simulator/" + provider + "/payments/" + providerReference + "/complete", Map.of(),
                Map.of("outcome", outcome, "duplicate_webhook", duplicateWebhook));
    }

    protected Map<String, Object> getPayment(TestMerchant merchant, String paymentId) {
        Response response = get(merchant, "/v1/payments/" + paymentId);
        assertThat(response.status()).isEqualTo(200);
        return response.body();
    }

    /** Creates, confirms (UPI intent) and completes a payment through the simulator; returns the final payment. */
    protected Map<String, Object> payAndSucceed(TestMerchant merchant, long amount) {
        String paymentId = str(createPayment(merchant, amount, "automatic"), "id");
        Response confirmed = confirm(merchant, paymentId, upi("intent"));
        simulate(str(confirmed.body(), "latest_attempt.provider"), str(confirmed.body(), "latest_attempt.provider_reference"),
                "success", false);
        Map<String, Object> payment = getPayment(merchant, paymentId);
        assertThat(str(payment, "status")).isEqualTo("succeeded");
        return payment;
    }

    /** Ledger balances of a merchant keyed by account type (normal-side balance). */
    protected Map<String, Long> ledgerBalances(TestMerchant merchant) {
        Response response = admin("GET", "/admin/v1/ledger/balances?merchant_id=" + merchant.id(), null);
        assertThat(response.status()).isEqualTo(200);
        Map<String, Long> balances = new HashMap<>();
        for (Map<String, Object> row : JsonPath.list(response.body(), "data")) {
            balances.merge(str(row, "account"), JsonPath.num(row, "balance"), Long::sum);
        }
        return balances;
    }

    /** Moves time forward and runs the resolver until nothing is due (bounded). */
    protected void resolveStatuses(Duration step, int rounds) {
        for (int i = 0; i < rounds; i++) {
            clock.advance(step);
            statusResolver.resolveDueAttempts();
            statusResolver.resolveDueRefunds();
        }
    }

    protected long count(String sql, Object... args) {
        var statement = jdbc.sql(sql);
        for (int i = 0; i < args.length; i++) {
            statement = statement.param(i + 1, args[i]);
        }
        return statement.query(Long.class).single();
    }
}
