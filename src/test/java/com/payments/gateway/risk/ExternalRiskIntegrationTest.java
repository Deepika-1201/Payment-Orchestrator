package com.payments.gateway.risk;

import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.support.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

/** The optional external fraud-scoring connector (ADR-016), against a local stub vendor. */
class ExternalRiskIntegrationTest extends IntegrationTest {

    private static final String SECRET = "risk_vendor_shared_secret_0001";
    private static final StubRiskVendor VENDOR = new StubRiskVendor();

    @DynamicPropertySource
    static void externalRisk(DynamicPropertyRegistry registry) {
        registry.add("pg.risk.external.url", VENDOR::url);
        registry.add("pg.risk.external.secret", () -> SECRET);
        registry.add("pg.risk.external.timeout", () -> "300ms");
    }

    @AfterAll
    static void stopVendor() {
        VENDOR.close();
    }

    @BeforeEach
    void resetVendor() {
        VENDOR.respond(200, "{\"decision\":\"allow\"}", Duration.ZERO);
    }

    @Test
    void vendorReceivesASignedRequestAndAnAllowLetsThePaymentThroughUnflagged() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_000, "automatic"), "id");

        Response confirmed = confirm(merchant, paymentId, upi("intent"));

        assertThat(str(confirmed.body(), "status")).isEqualTo("requires_action");
        assertThat(openReviews(merchant)).isEmpty();
        StubRiskVendor.Call call = VENDOR.lastCall();
        Matcher signature = Pattern.compile("t=(\\d+),v1=([0-9a-f]{64})").matcher(call.signature());
        assertThat(signature.matches()).as(call.signature()).isTrue();
        assertThat(signature.group(2)).isEqualTo(Hashing.hmacSha256Hex(SECRET, signature.group(1) + "." + call.body()));
        Map<String, Object> sent = json.read(call.body(), new TypeReference<Map<String, Object>>() { });
        assertThat(sent).containsEntry("payment_id", paymentId).containsEntry("merchant_id", merchant.id())
                .containsEntry("amount", 10_000).containsEntry("method", "upi").containsEntry("upi_flow", "intent");
    }

    @Test
    void vendorBlockFailsThePaymentKeepingOnlyWellFormedReasons() {
        VENDOR.respond(200, "{\"decision\":\"block\",\"reasons\":[\"device_blacklisted\",\"<script>alert(1)</script>\"]}",
                Duration.ZERO);
        TestMerchant merchant = createMerchant(ALPHA);

        Response confirmed = confirm(merchant, str(createPayment(merchant, 10_000, "automatic"), "id"), upi("intent"));

        assertThat(str(confirmed.body(), "status")).isEqualTo("failed");
        assertThat(str(confirmed.body(), "last_error.code")).isEqualTo("risk_blocked");
        assertThat(str(confirmed.body(), "last_error.message")).contains("external:device_blacklisted").doesNotContain("script");
    }

    @Test
    void vendorTimeoutSendsThePaymentToReviewInsteadOfFailingOrSilentlyAllowingIt() {
        VENDOR.respond(200, "{\"decision\":\"allow\"}", Duration.ofSeconds(3));
        TestMerchant merchant = createMerchant(ALPHA);
        long started = System.nanoTime();

        Response confirmed = confirm(merchant, str(createPayment(merchant, 10_000, "automatic"), "id"), upi("intent"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("bounded by the vendor timeout").isLessThan(Duration.ofSeconds(2));
        assertThat(str(confirmed.body(), "status")).isEqualTo("requires_action");
        Map<String, Object> item = openReviews(merchant).getFirst();
        assertThat(item.get("reasons")).isEqualTo(List.of("risk_review"));
        assertThat(item.get("risk_reasons")).isEqualTo(List.of("external_risk_unavailable"));
    }

    @Test
    void vendorErrorsAndUnknownDecisionsAlsoGoToReview() {
        TestMerchant merchant = createMerchant(ALPHA);
        VENDOR.respond(503, "{\"error\":\"overloaded\"}", Duration.ZERO);
        confirm(merchant, str(createPayment(merchant, 10_000, "automatic"), "id"), upi("intent"));
        VENDOR.respond(200, "{\"decision\":\"maybe\"}", Duration.ZERO);
        confirm(merchant, str(createPayment(merchant, 10_000, "automatic"), "id"), upi("intent"));

        assertThat(openReviews(merchant)).extracting(item -> item.get("risk_reasons"))
                .containsExactly(List.of("external_risk_unavailable"), List.of("external_risk_invalid_response"));
    }

    private List<Map<String, Object>> openReviews(TestMerchant merchant) {
        return list(admin("GET", "/admin/v1/reviews?merchant_id=" + merchant.id(), null).body(), "data");
    }

    /** A scriptable fraud vendor on a random local port. */
    static final class StubRiskVendor implements AutoCloseable {

        record Call(String signature, String body) {
        }

        private final HttpServer server;
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private volatile int status;
        private volatile String responseBody;
        private volatile Duration delay = Duration.ZERO;

        StubRiskVendor() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/score", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                calls.add(new Call(exchange.getRequestHeaders().getFirst("PG-Signature"), body));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                } catch (IOException ignored) {
                    // the gateway gave up waiting (timeout test)
                }
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/score";
        }

        void respond(int status, String body, Duration delay) {
            this.status = status;
            this.responseBody = body;
            this.delay = delay;
        }

        Call lastCall() {
            assertThat(calls).isNotEmpty();
            return calls.getLast();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
