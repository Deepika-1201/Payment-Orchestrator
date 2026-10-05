package com.payments.gateway.provider.cashfree;

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
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import tools.jackson.databind.JsonNode;

/**
 * Cashfree PG REST calls with ADR-005 failure classification: a request that never reached Cashfree is unavailable
 * (failover allowed); one that may have been processed is a timeout (outcome unknown, resolved by status checks).
 */
final class CashfreeApi {

    static final String CODE = "CASHFREE";

    /** Cashfree answered 4xx other than 401/429: the request was understood and refused. */
    static final class ApiError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String code;

        ApiError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        String code() {
            return code;
        }

        /** Cashfree answers 409 when an {@code order_id}, {@code link_id} or {@code refund_id} is reused. */
        boolean isDuplicate() {
            return status == 409;
        }

        boolean isNotFound() {
            return status == 404;
        }
    }

    private final URI baseUrl;
    private final Duration readTimeout;
    private final String apiVersion;
    private final JsonCodec json;
    private final HttpClient http;

    CashfreeApi(URI baseUrl, Duration connectTimeout, Duration readTimeout, String apiVersion, JsonCodec json) {
        this.baseUrl = baseUrl;
        this.readTimeout = readTimeout;
        this.apiVersion = apiVersion;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    JsonNode get(MerchantAccount account, String path) {
        return send(account, HttpRequest.newBuilder(uri(path)).GET(), false);
    }

    JsonNode post(MerchantAccount account, String path, Map<String, ?> body) {
        return send(account, HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(body))), true);
    }

    /** A POST that only reads (Cashfree's settlement reports), so its failures are classified like a GET's. */
    JsonNode search(MerchantAccount account, String path, Map<String, ?> body) {
        return send(account, HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(body))), false);
    }

    JsonNode parse(String body) {
        return json.read(body, JsonNode.class);
    }

    /** Our ids only contain [A-Za-z0-9_-]; Cashfree's (CFPay_...) too, but encode anyway. */
    static String segment(String id) {
        return URLEncoder.encode(id, StandardCharsets.UTF_8);
    }

    private URI uri(String path) {
        return URI.create(baseUrl.toString().replaceAll("/+$", "") + path);
    }

    private JsonNode send(MerchantAccount account, HttpRequest.Builder request, boolean mayChangeState) {
        String clientId = account.credential(CashfreePaymentProvider.CLIENT_ID)
                .orElseThrow(() -> new ProviderCredentialsException(CODE, "client_id is not configured"));
        String clientSecret = account.credential(CashfreePaymentProvider.CLIENT_SECRET)
                .orElseThrow(() -> new ProviderCredentialsException(CODE, "client_secret is not configured"));
        HttpRequest built = request.timeout(readTimeout)
                .header("x-client-id", clientId)
                .header("x-client-secret", clientSecret)
                .header("x-api-version", apiVersion)
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
        JsonNode error = parseQuietly(response.body());
        String message = error.path("message").asString("HTTP " + status);
        if (status == 401 || "authentication_error".equals(error.path("type").asString(""))) {
            throw new ProviderCredentialsException(CODE, "Cashfree rejected the API keys: " + message);
        }
        if (status == 429) {
            throw new ProviderUnavailableException(CODE, "rate limited by Cashfree");
        }
        if (status >= 500) {
            if (mayChangeState) {
                throw new ProviderTimeoutException(CODE, "Cashfree answered " + status + "; the request may have been processed");
            }
            throw new ProviderUnavailableException(CODE, "Cashfree answered " + status);
        }
        throw new ApiError(status, error.path("code").asString("request_failed"), message);
    }

    private JsonNode parseQuietly(String body) {
        try {
            return json.read(body, JsonNode.class);
        } catch (RuntimeException e) {
            return json.read("{}", JsonNode.class);
        }
    }
}
