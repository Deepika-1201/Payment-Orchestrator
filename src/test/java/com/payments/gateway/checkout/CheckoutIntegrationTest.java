package com.payments.gateway.checkout;

import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.support.IntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static com.payments.gateway.support.JsonPath.num;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;

class CheckoutIntegrationTest extends IntegrationTest {

    private static final String RETURN_URL = "https://merchant.example/orders/42";

    private record Page(int status, String html, HttpHeaders headers) {
    }

    @Test
    void sessionsNeedAPaymentOfTheSameMerchantThatAwaitsAMethod() {
        TestMerchant merchant = createMerchant(ALPHA);
        TestMerchant other = createMerchant(ALPHA);
        Map<String, Object> payment = createPayment(merchant, 49_900, "automatic");
        String paymentId = str(payment, "id");
        Map<String, Object> request = Map.of("payment_id", paymentId, "return_url", RETURN_URL);

        Response created = assertContract("POST", "/v1/checkout-sessions", request,
                post(merchant, "/v1/checkout-sessions", key(), request));
        assertThat(created.status()).isEqualTo(201);
        assertThat(str(created.body(), "url")).startsWith("http://localhost:8080/checkout/");
        assertThat(Instant.parse(str(created.body(), "expires_at")))
                .isEqualTo(Instant.parse(str(payment, "expires_at")).plus(Duration.ofHours(1)));

        assertThat(assertContract("POST", "/v1/checkout-sessions", request,
                post(other, "/v1/checkout-sessions", key(), request)).status()).isEqualTo(404);
        confirm(merchant, paymentId, upi("intent"));
        Response afterConfirm = assertContract("POST", "/v1/checkout-sessions", request,
                post(merchant, "/v1/checkout-sessions", key(), request));
        assertThat(str(afterConfirm.body(), "code")).isEqualTo("payment_invalid_state");
    }

    @Test
    void pageOffersRoutableMethodsWithStrictHeadersAndEscapedContent() {
        TestMerchant merchant = createMerchant(ALPHA, BETA);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", 49_900);
        body.put("currency", "INR");
        body.put("merchant_order_id", "order_xss");
        body.put("description", "<script>alert(1)</script>");
        String paymentId = str(post(merchant, "/v1/payments", key(), body).body(), "id");

        Page page = open(session(merchant, paymentId));

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
        assertThat(page.html()).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>");
        assertThat(page.html()).contains("\u20B9499.00", "value=\"upi_collect\"", "value=\"upi_intent\"", "value=\"card\"",
                "value=\"netbanking\"", "<option value=\"HDFC\">HDFC Bank</option>");
        assertSecurityHeaders(page);
        Matcher style = Pattern.compile("<style>(.*?)</style>").matcher(page.html());
        assertThat(style.find()).isTrue();
        String styleHash = Base64.getEncoder().encodeToString(Hashing.sha256(style.group(1)));
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(csp -> assertThat(csp)
                .contains("'sha256-" + styleHash + "'"));
    }

    @Test
    void upiCollectFromTheHostedPageCompletesThePayment() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 49_900, "automatic"), "id");
        String path = session(merchant, paymentId);

        Page submitted = submit(path, Map.of("method", "upi_collect", "vpa", "customer@okbank"));

        assertThat(submitted.status()).isEqualTo(303);
        assertThat(submitted.headers().firstValue("Location")).hasValueSatisfying(location -> assertThat(location).endsWith(path));
        Page waiting = open(path);
        assertThat(waiting.html()).contains("approve the payment request", "http-equiv=\"refresh\"");
        Map<String, Object> payment = getPayment(merchant, paymentId);
        assertThat(str(payment, "latest_attempt.upi_flow")).isEqualTo("collect");

        simulate(ALPHA, str(payment, "latest_attempt.provider_reference"), "success", false);
        Page done = open(path);
        assertThat(done.html()).contains("Payment successful", "href=\"" + RETURN_URL + "\"").doesNotContain("http-equiv");
        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
    }

    @Test
    void invalidChoicesAreRejectedWithoutStartingAnAttempt() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 49_900, "automatic"), "id");
        String path = session(merchant, paymentId);

        assertThat(submit(path, Map.of("method", "upi_collect", "vpa", "not a vpa")).headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).endsWith(path + "?error=invalid_vpa"));
        assertThat(submit(path, Map.of("method", "netbanking", "bank", "EVIL")).headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).endsWith(path + "?error=invalid_bank"));
        assertThat(submit(path, Map.of("method", "crypto")).headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).endsWith(path + "?error=method_unavailable"));

        assertThat(open(path + "?error=invalid_vpa").html()).contains("Enter a valid UPI ID");
        assertThat(open(path + "?error=%3Cb%3Ehi").html()).doesNotContain("<b>hi").doesNotContain("role=\"alert\"");
        assertThat(num(getPayment(merchant, paymentId), "attempt_count")).isZero();
    }

    @Test
    void cardPaymentsContinueOnThePspPageWhichReturnsToCheckout() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 49_900, "automatic"), "id");
        String url = sessionUrl(merchant, paymentId);
        String path = URI.create(url).getPath();

        assertThat(submit(path, Map.of("method", "card")).status()).isEqualTo(303);

        Page page = open(path);
        assertThat(page.html()).contains(">Continue</a>", "/simulator/" + ALPHA + "/checkout/");
        String reference = str(getPayment(merchant, paymentId), "latest_attempt.provider_reference");
        MockPaymentProvider alpha = mockProviders.stream().filter(p -> p.code().equals(ALPHA)).findFirst().orElseThrow();
        assertThat(alpha.psp().find(reference, null).orElseThrow().returnUrl()).isEqualTo(url);
    }

    @Test
    void aDoubleSubmitStartsOnlyOneAttempt() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 49_900, "automatic"), "id");
        String path = session(merchant, paymentId);

        assertThat(submit(path, Map.of("method", "upi_intent")).status()).isEqualTo(303);
        assertThat(submit(path, Map.of("method", "upi_intent")).status()).isEqualTo(303);

        assertThat(num(getPayment(merchant, paymentId), "attempt_count")).isEqualTo(1);
        assertThat(open(path).html()).contains("href=\"upi://pay?");
    }

    @Test
    void aDeclinedAttemptLetsTheCustomerTryAgain() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 10_003, "automatic"), "id");
        String path = session(merchant, paymentId);

        submit(path, Map.of("method", "card"));

        assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("requires_payment_method");
        assertThat(open(path).html()).contains("previous attempt was not successful", "value=\"upi_collect\"");
    }

    @Test
    void unknownMalformedAndExpiredLinksAreNotFound() {
        TestMerchant merchant = createMerchant(ALPHA);
        String paymentId = str(createPayment(merchant, 49_900, "automatic"), "id");
        String path = session(merchant, paymentId);

        Page malformed = open("/checkout/not-a-token");
        assertThat(malformed.status()).isEqualTo(404);
        assertThat(malformed.html()).contains("invalid or has expired");
        assertSecurityHeaders(malformed);
        assertThat(open("/checkout/" + Hashing.randomToken(32)).status()).isEqualTo(404);
        assertThat(open(path).status()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(15).plus(Duration.ofHours(1)).plusSeconds(1));

        assertThat(open(path).status()).isEqualTo(404);
        assertThat(submit(path, Map.of("method", "upi_intent")).status()).isEqualTo(404);
    }

    private static void assertSecurityHeaders(Page page) {
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(csp -> assertThat(csp)
                .contains("default-src 'none'", "form-action 'self'", "frame-ancestors 'none'"));
        assertThat(page.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(page.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(page.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(page.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    private String session(TestMerchant merchant, String paymentId) {
        return URI.create(sessionUrl(merchant, paymentId)).getPath();
    }

    private String sessionUrl(TestMerchant merchant, String paymentId) {
        Response created = post(merchant, "/v1/checkout-sessions", key(),
                Map.of("payment_id", paymentId, "return_url", RETURN_URL));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        return str(created.body(), "url");
    }

    private Page open(String path) {
        return exchange(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET());
    }

    private Page submit(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return exchange(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private Page exchange(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Page(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
