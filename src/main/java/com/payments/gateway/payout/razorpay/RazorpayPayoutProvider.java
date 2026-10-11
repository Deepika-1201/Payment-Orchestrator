package com.payments.gateway.payout.razorpay;

import com.payments.gateway.payout.spi.PayoutProvider;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.Money;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(prefix = "pg.payouts.razorpay", name = "enabled", havingValue = "true")
public class RazorpayPayoutProvider implements PayoutProvider {
    public static final String CODE = "RAZORPAYX";
    private static final String LIVE_API = "https://api.razorpay.com/v1";
    private final RazorpayPayoutProperties properties;
    private final JsonCodec json;
    private final HttpClient http;

    private record Response(int status, JsonNode body) {
    }

    public RazorpayPayoutProvider(RazorpayPayoutProperties properties, JsonCodec json, Environment environment) {
        this.properties = properties;
        this.json = json;
        boolean production = environment.matchesProfiles("prod");
        if (!properties.keyId().startsWith(production ? "rzp_live_" : "rzp_test_")) {
            throw new IllegalArgumentException("RazorpayX credential mode does not match the deployment");
        }
        if (production && !LIVE_API.equals(properties.baseUrl().toString())) {
            throw new IllegalArgumentException("Production RazorpayX must use the official HTTPS API");
        }
        http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String sourceAccount() {
        return properties.sourceAccount();
    }

    @Override
    public void validate(Instruction instruction) {
        Destination destination = instruction.destination();
        if (!instruction.id().matches("[A-Za-z0-9_ -]{4,36}") || !instruction.amount().inSettlementCurrency()
                || instruction.amount().amount() < 100 || !sourceAccount().equals(instruction.sourceAccount())
                || destination == null || destination.beneficiary() == null || !destination.beneficiary().matches("[A-Za-z0-9 '\\u2019_/.()\\-]{4,120}")
                || destination.accountNumber() == null || !destination.accountNumber().matches("[A-Za-z0-9]{5,35}")
                || destination.ifsc() == null || !destination.ifsc().matches("[A-Z]{4}0[A-Z0-9]{6}")
                || instruction.merchantId().length() < 3 || instruction.merchantId().length() > 40) {
            throw new IllegalArgumentException("Payout instruction is outside RazorpayX bank-account constraints");
        }
    }

    @Override
    public Result create(Instruction instruction) {
        validate(instruction);
        Map<String, Object> body = Map.of("account_number", instruction.sourceAccount(), "amount", instruction.amount().amount(),
                "currency", "INR", "mode", "NEFT", "purpose", "payout", "queue_if_low_balance", false,
                "reference_id", instruction.id(), "narration", "Merchant settlement", "fund_account", Map.of(
                        "account_type", "bank_account", "bank_account", Map.of("name", instruction.destination().beneficiary(),
                                "account_number", instruction.destination().accountNumber(), "ifsc", instruction.destination().ifsc()),
                        "contact", Map.of("name", instruction.merchantId(), "type", "vendor", "reference_id", instruction.merchantId())));
        Response response = send(HttpRequest.newBuilder(uri("/payouts")).header("Content-Type", "application/json")
                .header("X-Payout-Idempotency", instruction.id()).POST(HttpRequest.BodyPublishers.ofString(json.write(body))));
        return successful(response) ? resolveDestination(parse(response.body())) : Result.unknown("provider_http_" + response.status());
    }

    @Override
    public Result fetch(Instruction instruction, String providerReference) {
        String path = providerReference == null
                ? "/payouts?account_number=" + encode(instruction.sourceAccount()) + "&reference_id=" + encode(instruction.id()) + "&count=2"
                : "/payouts/" + encode(providerReference);
        Response response = send(HttpRequest.newBuilder(uri(path)).GET());
        if (response.status() == 404) {
            return new Result(Outcome.NOT_FOUND, null, instruction.id(), null, null, null, "provider_not_found");
        }
        if (!successful(response)) {
            return Result.unknown("provider_http_" + response.status());
        }
        JsonNode item = response.body();
        if (providerReference == null) {
            JsonNode items = item.path("items");
            if (!items.isArray()) {
                return Result.unknown("malformed_payout_listing");
            }
            if (items.isEmpty()) {
                return new Result(Outcome.NOT_FOUND, null, instruction.id(), null, null, null, "provider_not_found");
            }
            if (items.size() != 1) {
                return Result.unknown("multiple_payout_matches");
            }
            item = items.get(0);
        }
        String source = text(item, "debit_account_number");
        if (source != null && !source.equals(instruction.sourceAccount())) {
            return Result.unknown("source_account_mismatch");
        }
        return resolveDestination(parse(item));
    }

    @Override
    public Result resolveDestination(Result result) {
        if (result.destination() != null || result.destinationReference() == null) {
            return result;
        }
        Response account = send(HttpRequest.newBuilder(uri("/fund_accounts/" + encode(result.destinationReference()))).GET());
        if (!successful(account)) {
            throw new IllegalStateException("Payout destination lookup is unavailable");
        }
        if (!result.destinationReference().equals(text(account.body(), "id"))) {
            throw new IllegalStateException("Payout destination reference mismatch");
        }
        Destination destination = destination(account.body());
        return new Result(result.outcome(), result.providerReference(), result.referenceId(), result.amount(), destination,
                result.bankReference(), result.reason(), result.destinationReference());
    }

    @Override
    public Event parseWebhook(Map<String, String> headers, String body) {
        String signature = header(headers, "X-Razorpay-Signature");
        if (!Hashing.constantTimeEquals(signature, Hashing.hmacSha256Hex(properties.webhookSecret(), body))) {
            throw new InvalidWebhook("Invalid RazorpayX signature");
        }
        JsonNode root = json.read(body, JsonNode.class);
        if (!properties.accountId().equals(text(root, "account_id"))) {
            throw new InvalidWebhook("RazorpayX webhook account mismatch");
        }
        JsonNode payout = root.path("payload").path("payout").path("entity");
        if (!payout.isObject() || text(payout, "reference_id") == null) {
            return null;
        }
        String source = text(payout, "debit_account_number");
        if (source != null && !source.equals(properties.sourceAccount())) {
            throw new InvalidWebhook("RazorpayX webhook source account mismatch");
        }
        String eventId = header(headers, "X-Razorpay-Event-Id");
        if (eventId == null || eventId.isBlank()) {
            eventId = HexFormat.of().formatHex(Hashing.sha256(body));
        }
        return new Event(eventId, text(payout, "reference_id"), parse(payout));
    }

    private Result parse(JsonNode payout) {
        String status = text(payout, "status");
        String reference = text(payout, "reference_id");
        String providerReference = text(payout, "id");
        JsonNode amount = payout.path("amount");
        String currency = text(payout, "currency");
        if (status == null || reference == null || providerReference == null || currency == null
                || !amount.isIntegralNumber() || !amount.canConvertToLong() || amount.asLong() <= 0) {
            return Result.unknown("malformed_payout_evidence");
        }
        Outcome outcome = switch (status) {
            case "processed" -> Outcome.SUCCEEDED;
            case "reversed" -> Outcome.RETURNED;
            case "rejected", "cancelled" -> Outcome.REJECTED;
            case "failed" -> Outcome.FAILURE_UNCONFIRMED;
            case "pending", "queued", "scheduled", "processing" -> Outcome.PENDING;
            default -> Outcome.UNKNOWN;
        };
        return new Result(outcome, providerReference, reference, Money.of(amount.asLong(), currency),
                destination(payout.path("fund_account")), text(payout, "utr"), "provider_" + status, text(payout, "fund_account_id"));
    }

    private static Destination destination(JsonNode account) {
        if (!"bank_account".equals(text(account, "account_type"))) {
            return null;
        }
        JsonNode bank = account.path("bank_account");
        String number = text(bank, "account_number");
        String ifsc = text(bank, "ifsc");
        return number == null || ifsc == null ? null : new Destination(text(bank, "name"), number, ifsc);
    }

    private Response send(HttpRequest.Builder builder) {
        String credentials = Base64.getEncoder().encodeToString((properties.keyId() + ":" + properties.keySecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = builder.timeout(properties.readTimeout()).header("Authorization", "Basic " + credentials)
                .header("Accept", "application/json").build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body().isBlank() ? json.read("{}", JsonNode.class) : json.read(response.body(), JsonNode.class);
            return new Response(response.statusCode(), body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Payout HTTP call interrupted");
        } catch (IOException exception) {
            throw new IllegalStateException("Payout HTTP outcome is unknown");
        }
    }

    private URI uri(String path) {
        String base = properties.baseUrl().toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean successful(Response response) {
        return response.status() >= 200 && response.status() < 300;
    }

    private static String text(JsonNode value, String name) {
        JsonNode field = value.path(name);
        return field.isString() ? field.asString() : null;
    }

    private static String header(Map<String, String> headers, String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
}