package com.payments.gateway.provider.razorpay;

import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.ProviderCredentialsException;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.shared.json.JsonCodec;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import javax.net.ssl.SSLHandshakeException;
import tools.jackson.databind.JsonNode;

/**
 * Razorpay REST calls with ADR-005 failure classification: a request that never reached Razorpay is unavailable
 * (failover allowed); one that may have been processed is a timeout (outcome unknown, resolved by status checks).
 */
final class RazorpayApi {

    static final String CODE = "RAZORPAY";

    /** Razorpay answered 4xx other than 401/429: the request was understood and refused. */
    static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String code;
        private final String reason;

        BadRequest(int status, String code, String description, String reason) {
            super(description);
            this.status = status;
            this.code = code;
            this.reason = reason;
        }

        int status() {
            return status;
        }

        String code() {
            return code;
        }

        String reason() {
            return reason;
        }

        /**
         * Razorpay treats an order's or refund's {@code receipt} and a link's {@code reference_id} as idempotency keys and
         * rejects a repeat with "Duplicate request..." or "payment link creation with reference ID already attempted".
         */
        boolean isDuplicate() {
            String message = getMessage() == null ? "" : getMessage().toLowerCase(java.util.Locale.ROOT);
            return message.contains("duplicate") || message.contains("already attempted");
        }

        boolean isNotFound() {
            return status == 404 || (getMessage() != null
                    && getMessage().toLowerCase(java.util.Locale.ROOT).contains("does not exist"));
        }
    }

    private final URI baseUrl;
    private final Duration readTimeout;
    private final String requiredKeyPrefix;
    private final JsonCodec json;
    private final HttpClient http;

    /**
     * {@code requiredKeyPrefix} is {@code rzp_test_} on a sandbox deployment and {@code rzp_live_} in production: a
     * test key in production would report payments that moved no money, a live key in a sandbox would charge customers.
     */
    RazorpayApi(URI baseUrl, Duration connectTimeout, Duration readTimeout, String requiredKeyPrefix, JsonCodec json) {
        this.baseUrl = baseUrl;
        this.readTimeout = readTimeout;
        this.requiredKeyPrefix = requiredKeyPrefix;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    JsonNode get(MerchantAccount account, String path, Map<String, String> query) {
        String queryString = query.isEmpty() ? "" : "?" + query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(account, HttpRequest.newBuilder(uri(path + queryString)).GET(), false);
    }

    JsonNode post(MerchantAccount account, String path, Map<String, ?> body) {
        return send(account, HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(body))), true);
    }

    JsonNode parse(String body) {
        return json.read(body, JsonNode.class);
    }

    private URI uri(String path) {
        return URI.create(baseUrl.toString().replaceAll("/+$", "") + path);
    }

    private JsonNode send(MerchantAccount account, HttpRequest.Builder request, boolean mayChangeState) {
        String keyId = account.credential(RazorpayPaymentProvider.KEY_ID)
                .orElseThrow(() -> new ProviderCredentialsException(CODE, "key_id is not configured"));
        if (!keyId.startsWith(requiredKeyPrefix)) {
            throw new ProviderCredentialsException(CODE, "key_id must be a " + requiredKeyPrefix + " key on this deployment");
        }
        String keySecret = account.credential(RazorpayPaymentProvider.KEY_SECRET)
                .orElseThrow(() -> new ProviderCredentialsException(CODE, "key_secret is not configured"));
        String basic = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest built = request.timeout(readTimeout)
                .header("Authorization", "Basic " + basic)
                .header("Accept", "application/json")
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(built, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException | ConnectException | SSLHandshakeException e) {
            throw new ProviderUnavailableException(CODE, "could not connect: " + e.getClass().getSimpleName(), e);
        } catch (HttpTimeoutException e) {
            throw new ProviderTimeoutException(CODE, "no response within " + readTimeout, e);
        } catch (IOException e) {
            if (!mayChangeState) {
                throw new ProviderUnavailableException(CODE, "connection failed: " + e.getClass().getSimpleName(), e);
            }
            throw new ProviderTimeoutException(CODE, "connection failed after sending: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderTimeoutException(CODE, "interrupted", e);
        }
        return classify(response, mayChangeState);
    }

    private JsonNode classify(HttpResponse<String> response, boolean mayChangeState) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return json.read(response.body(), JsonNode.class);
        }
        if (status == 401) {
            throw new ProviderCredentialsException(CODE, "Razorpay rejected the API key");
        }
        if (status == 429) {
            throw new ProviderUnavailableException(CODE, "rate limited by Razorpay");
        }
        if (status >= 500) {
            if (mayChangeState) {
                throw new ProviderTimeoutException(CODE, "Razorpay answered " + status + "; the request may have been processed");
            }
            throw new ProviderUnavailableException(CODE, "Razorpay answered " + status);
        }
        JsonNode error = parseQuietly(response.body()).path("error");
        String description = error.path("description").asString("HTTP " + status);
        if (isAuthenticationFailure(description)) {
            throw new ProviderCredentialsException(CODE, "Razorpay rejected the API key: " + description);
        }
        throw new BadRequest(status, error.path("code").asString("BAD_REQUEST_ERROR"), description,
                error.path("reason").asString(null));
    }

    /** Razorpay reports bad or expired keys with HTTP 400 as well as 401. */
    private static boolean isAuthenticationFailure(String description) {
        String message = description.toLowerCase(java.util.Locale.ROOT);
        return message.startsWith("authentication failed") || message.contains("api key provided is invalid")
                || message.contains("api secret provided is invalid");
    }

    private JsonNode parseQuietly(String body) {
        try {
            return json.read(body, JsonNode.class);
        } catch (RuntimeException e) {
            return json.read("{}", JsonNode.class);
        }
    }
}
