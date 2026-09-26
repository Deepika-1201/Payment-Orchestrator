package com.payments.gateway.risk.rules;

import com.payments.gateway.risk.RiskContext;
import com.payments.gateway.risk.RiskDecision;
import com.payments.gateway.risk.RiskProperties;
import com.payments.gateway.risk.RiskRule;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Asks an external fraud-scoring service for a decision (ADR-016). Runs before the payment row lock is taken, is
 * bounded by {@code pg.risk.external.timeout}, and fails open to REVIEW: an outage neither blocks good customers nor
 * lets a payment through unseen.
 *
 * <p>Request: {@code POST <url>} with a JSON body and {@code PG-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret,
 * t + "." + body)>}, the same scheme as merchant webhooks. Response: {@code {"decision": "allow|review|challenge|block",
 * "reasons": ["..."]}}.
 */
@Component
@ConditionalOnProperty(prefix = "pg.risk.external", name = "url")
public class ExternalRiskRule implements RiskRule {

    static final String SIGNATURE_HEADER = "PG-Signature";
    static final String UNAVAILABLE = "external_risk_unavailable";
    static final String INVALID_RESPONSE = "external_risk_invalid_response";

    private static final Logger log = LoggerFactory.getLogger(ExternalRiskRule.class);
    /** Vendor reasons end up in failure messages and the review queue, so only short codes are kept. */
    private static final Pattern REASON = Pattern.compile("[a-z0-9_.:-]{1,64}");
    private static final int MAX_REASONS = 5;

    record Request(String paymentId, String merchantId, long amount, String currency, String method, String upiFlow,
                   String vpa, String customerReference, String customerEmail, String ip, String deviceId,
                   int recentAttemptsByCustomer) {
    }

    record Response(String decision, List<String> reasons) {
    }

    private final RiskProperties.External config;
    private final HttpClient http;
    private final JsonCodec json;
    private final Clock clock;
    private final MeterRegistry meters;

    public ExternalRiskRule(RiskProperties properties, JsonCodec json, Clock clock, MeterRegistry meters) {
        this.config = properties.external();
        if (config.secret() == null || config.secret().isBlank()) {
            throw new IllegalStateException("pg.risk.external.secret is required when pg.risk.external.url is set");
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.json = json;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        String body = json.write(requestOf(context));
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(config.url())
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header(SIGNATURE_HEADER, "t=" + timestamp + ",v1=" + Hashing.hmacSha256Hex(config.secret(), timestamp + "." + body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        Timer.Sample sample = Timer.start(meters);
        RiskDecision decision = call(request, context.paymentId());
        String result = decision.reasons().contains(UNAVAILABLE) ? "error" : decision.outcome().name().toLowerCase(Locale.ROOT);
        sample.stop(meters.timer("pg.risk.external", "result", result));
        return decision;
    }

    private RiskDecision call(HttpRequest request, String paymentId) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("External risk service returned HTTP {} for payment {}", response.statusCode(), paymentId);
                return RiskDecision.of(RiskDecision.Outcome.REVIEW, UNAVAILABLE);
            }
            return toDecision(json.read(response.body(), Response.class));
        } catch (IOException | RuntimeException e) {
            log.warn("External risk check failed for payment {}: {}", paymentId, e.toString());
            return RiskDecision.of(RiskDecision.Outcome.REVIEW, UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RiskDecision.of(RiskDecision.Outcome.REVIEW, UNAVAILABLE);
        }
    }

    private static RiskDecision toDecision(Response response) {
        String decision = response == null || response.decision() == null ? "" : response.decision().toLowerCase(Locale.ROOT);
        RiskDecision.Outcome outcome = switch (decision) {
            case "allow" -> RiskDecision.Outcome.ALLOW;
            case "review" -> RiskDecision.Outcome.REVIEW;
            case "challenge" -> RiskDecision.Outcome.CHALLENGE;
            case "block" -> RiskDecision.Outcome.BLOCK;
            default -> null;
        };
        if (outcome == null) {
            return RiskDecision.of(RiskDecision.Outcome.REVIEW, INVALID_RESPONSE);
        }
        if (outcome == RiskDecision.Outcome.ALLOW) {
            return RiskDecision.allow();
        }
        List<String> reasons = response.reasons() == null ? List.of() : response.reasons().stream()
                .filter(r -> r != null && REASON.matcher(r).matches())
                .limit(MAX_REASONS)
                .map(r -> "external:" + r)
                .toList();
        return new RiskDecision(outcome, reasons.isEmpty() ? List.of("external:" + decision) : reasons);
    }

    private static Request requestOf(RiskContext c) {
        return new Request(c.paymentId(), c.merchantId(), c.amount().amount(), c.amount().currency(),
                c.method().type().name().toLowerCase(Locale.ROOT),
                c.method().upiFlow() == null ? null : c.method().upiFlow().name().toLowerCase(Locale.ROOT),
                c.method().vpa(), c.customerReference(), c.customerEmail(), c.ip(), c.deviceId(),
                c.recentAttemptsByCustomer());
    }
}
