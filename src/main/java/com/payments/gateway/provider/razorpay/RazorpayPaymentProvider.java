package com.payments.gateway.provider.razorpay;

import com.payments.gateway.provider.spi.CredentialField;
import com.payments.gateway.provider.spi.InboundWebhook;
import com.payments.gateway.provider.spi.InitiatePaymentRequest;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.provider.spi.ProviderCapabilities;
import com.payments.gateway.provider.spi.ProviderCapabilities.MethodSupport;
import com.payments.gateway.provider.spi.ProviderDisputeResult;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderFailure;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.VoidRequest;
import com.payments.gateway.provider.spi.WebhookVerificationException;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.UpiFlow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Razorpay adapter (ADR-030), built from Razorpay's API reference.
 * <ul>
 *   <li>UPI intent and QR use server-to-server UPI ({@code /payments/create/upi}) when Razorpay has enabled it;
 *       otherwise, and for cards and netbanking, the customer pays on a Razorpay-hosted Payment Link (ADR-008).</li>
 *   <li>Our attempt and refund ids are the Razorpay {@code receipt}/{@code reference_id} and are copied into
 *       {@code notes}, so every object can be found again after a timeout and every webhook maps back to us.</li>
 *   <li>Payments are captured automatically; Razorpay cannot void an authorization, so neither can this adapter.</li>
 * </ul>
 */
public final class RazorpayPaymentProvider implements PaymentProvider {

    static final String KEY_ID = "key_id";
    static final String KEY_SECRET = "key_secret";
    static final String WEBHOOK_SECRET = "webhook_secret";
    static final String ATTEMPT_NOTE = "pg_attempt_id";
    static final String REFUND_NOTE = "pg_refund_id";

    private static final List<CredentialField> CREDENTIALS = List.of(
            new CredentialField(KEY_ID, false, true),
            new CredentialField(KEY_SECRET, true, true),
            new CredentialField(WEBHOOK_SECRET, true, true));
    private static final long UPI_MAX = 10_000_000L;
    private static final long CARD_MAX = 100_000_000L;
    // Razorpay rejects Payment Links that expire less than 15 minutes after creation.
    private static final Duration MIN_LINK_TTL = Duration.ofMinutes(16);

    private final RazorpayProperties properties;
    private final RazorpayApi api;
    private final Clock clock;
    private final ProviderCapabilities capabilities;

    RazorpayPaymentProvider(RazorpayProperties properties, RazorpayApi api, Clock clock) {
        this.properties = properties;
        this.api = api;
        this.clock = clock;
        Set<UpiFlow> upiFlows = properties.upiS2s() ? EnumSet.of(UpiFlow.INTENT, UpiFlow.QR) : EnumSet.of(UpiFlow.INTENT);
        this.capabilities = new ProviderCapabilities(Map.of(
                MethodType.UPI, new MethodSupport(upiFlows, 100, UPI_MAX, false),
                MethodType.CARD, new MethodSupport(Set.of(), 100, CARD_MAX, false),
                MethodType.NETBANKING, new MethodSupport(Set.of(), 100, CARD_MAX, false)),
                Set.of("INR"), false, true, false);
    }

    @Override
    public String code() {
        return RazorpayApi.CODE;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public List<CredentialField> credentialFields() {
        return CREDENTIALS;
    }

    // ------------------------------------------------------------------ payments

    @Override
    public ProviderPaymentResult initiatePayment(MerchantAccount account, InitiatePaymentRequest request) {
        boolean s2s = request.method().type() == MethodType.UPI && properties.upiS2s()
                && request.customerPhone() != null && request.customerEmail() != null;
        return s2s ? initiateUpiS2s(account, request) : initiateHostedPage(account, request);
    }

    private ProviderPaymentResult initiateUpiS2s(MerchantAccount account, InitiatePaymentRequest request) {
        JsonNode order = createOrFindOrder(account, request);
        String orderId = text(order, "id");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amount().amount());
        body.put("currency", request.amount().currency());
        body.put("order_id", orderId);
        body.put("method", "upi");
        body.put("email", request.customerEmail());
        body.put("contact", request.customerPhone());
        putIfPresent(body, "ip", request.clientIp());
        putIfPresent(body, "description", request.description());
        body.put("notes", notes(request));
        body.put("upi", Map.of("flow", "intent"));
        JsonNode created;
        try {
            created = api.post(account, "/payments/create/upi", body);
        } catch (RazorpayApi.BadRequest e) {
            return ProviderPaymentResult.failed(orderId, failure(e), "create_upi_rejected");
        }
        String link = text(created, "link");
        if (link == null) {
            return ProviderPaymentResult.pending(orderId, "created");
        }
        NextAction next = request.method().upiFlow() == UpiFlow.QR
                ? NextAction.displayQr(link, clock.instant().plus(properties.hostedPageTtl()))
                : NextAction.upiIntent(link);
        return ProviderPaymentResult.requiresAction(orderId, next, "created");
    }

    private JsonNode createOrFindOrder(MerchantAccount account, InitiatePaymentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amount().amount());
        body.put("currency", request.amount().currency());
        body.put("receipt", request.attemptId());
        body.put("notes", notes(request));
        try {
            return api.post(account, "/orders", body);
        } catch (RazorpayApi.BadRequest e) {
            if (e.isDuplicate()) {
                return findOrderByReceipt(account, request.attemptId())
                        .orElseThrow(() -> new IllegalStateException("Razorpay reports a duplicate receipt it cannot find", e));
            }
            throw e;
        }
    }

    private ProviderPaymentResult initiateHostedPage(MerchantAccount account, InitiatePaymentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amount().amount());
        body.put("currency", request.amount().currency());
        body.put("reference_id", request.attemptId());
        putIfPresent(body, "description", request.description());
        // No customer object: the hosted page does not prefill it, and accounts that require customer.name reject it.
        body.put("notify", Map.of("sms", false, "email", false));
        body.put("reminder_enable", false);
        body.put("notes", notes(request));
        Duration ttl = properties.hostedPageTtl().compareTo(MIN_LINK_TTL) < 0 ? MIN_LINK_TTL : properties.hostedPageTtl();
        body.put("expire_by", clock.instant().plus(ttl).getEpochSecond());
        if (request.returnUrl() != null && request.returnUrl().startsWith("https://")) {
            body.put("callback_url", request.returnUrl());
            body.put("callback_method", "get");
        }
        JsonNode link;
        try {
            link = api.post(account, "/payment_links", body);
        } catch (RazorpayApi.BadRequest e) {
            if (e.isDuplicate()) {
                Optional<JsonNode> existing = findLinkByReference(account, request.attemptId());
                if (existing.isPresent()) {
                    return linkResult(account, existing.get());
                }
            }
            return ProviderPaymentResult.failed(null, failure(e), "payment_link_rejected");
        }
        return ProviderPaymentResult.requiresAction(text(link, "id"), NextAction.redirect(text(link, "short_url")),
                text(link, "status"));
    }

    @Override
    public ProviderPaymentResult fetchPaymentStatus(MerchantAccount account, PaymentStatusQuery query) {
        String reference = query.providerReference();
        if (reference == null) {
            // A link's order carries the link's reference_id as its receipt, so look for a link first: its status
            // (expired, cancelled) is what decides an unpaid hosted-page attempt.
            Optional<JsonNode> link = findLinkByReference(account, query.attemptId());
            if (link.isPresent()) {
                return linkResult(account, link.get());
            }
            return findOrderByReceipt(account, query.attemptId())
                    .map(order -> orderResult(account, order))
                    .orElseGet(ProviderPaymentResult::notFound);
        }
        try {
            if (reference.startsWith("plink_")) {
                return linkResult(account, api.get(account, "/payment_links/" + reference, Map.of()));
            }
            return orderResult(account, api.get(account, "/orders/" + reference, Map.of()));
        } catch (RazorpayApi.BadRequest e) {
            if (e.isNotFound()) {
                return ProviderPaymentResult.notFound();
            }
            throw e;
        }
    }

    private ProviderPaymentResult orderResult(MerchantAccount account, JsonNode order) {
        String orderId = text(order, "id");
        JsonNode payments = api.get(account, "/orders/" + orderId + "/payments", Map.of()).path("items");
        Instant windowEnds = Instant.ofEpochSecond(order.path("created_at").asLong(0)).plus(properties.hostedPageTtl());
        return fromPayments(orderId, payments, clock.instant().isAfter(windowEnds));
    }

    private ProviderPaymentResult linkResult(MerchantAccount account, JsonNode link) {
        String linkId = text(link, "id");
        String status = text(link, "status");
        String orderId = text(link, "order_id");
        if (orderId != null) {
            JsonNode payments = api.get(account, "/orders/" + orderId + "/payments", Map.of()).path("items");
            ProviderPaymentResult fromPayments = fromPayments(linkId, payments, false);
            if (fromPayments.outcome() == ProviderPaymentResult.Outcome.SUCCEEDED
                    || fromPayments.outcome() == ProviderPaymentResult.Outcome.AUTHORIZED) {
                return fromPayments;
            }
        }
        if ("expired".equals(status) || "cancelled".equals(status)) {
            return ProviderPaymentResult.failed(linkId, new ProviderFailure("payment_link_" + status,
                    "The customer did not pay before the payment page " + status, FailureCategory.CUSTOMER), status);
        }
        return ProviderPaymentResult.requiresAction(linkId, null, status);
    }

    /**
     * Razorpay may capture a payment it earlier reported as failed (UPI retries in the customer's app), so a failure
     * is final only once the payment window has closed.
     */
    private static ProviderPaymentResult fromPayments(String reference, JsonNode payments, boolean windowClosed) {
        JsonNode lastFailed = null;
        for (JsonNode payment : payments) {
            switch (status(payment)) {
                case "captured", "refunded" -> {
                    return withCard(ProviderPaymentResult.succeeded(reference, amount(payment), "captured"), payment);
                }
                case "authorized" -> {
                    return withCard(ProviderPaymentResult.authorized(reference, amount(payment), "authorized"), payment);
                }
                case "failed" -> lastFailed = payment;
                default -> {
                }
            }
        }
        if (lastFailed != null && windowClosed) {
            return ProviderPaymentResult.failed(reference, paymentFailure(lastFailed), "failed");
        }
        return lastFailed != null ? ProviderPaymentResult.pending(reference, "failed_retryable")
                : ProviderPaymentResult.requiresAction(reference, null, "created");
    }

    @Override
    public ProviderPaymentResult capture(MerchantAccount account, CaptureRequest request) {
        Optional<JsonNode> payment = settledOrAuthorizedPayment(account, request.providerReference());
        if (payment.isEmpty()) {
            return ProviderPaymentResult.failed(request.providerReference(),
                    new ProviderFailure("nothing_to_capture", "Razorpay has no authorized payment for this attempt",
                            FailureCategory.PROVIDER), "not_authorized");
        }
        JsonNode found = payment.get();
        if (!"authorized".equals(text(found, "status"))) {
            return withCard(ProviderPaymentResult.succeeded(request.providerReference(), amount(found), "captured"), found);
        }
        try {
            JsonNode captured = api.post(account, "/payments/" + text(found, "id") + "/capture",
                    Map.of("amount", request.amount().amount(), "currency", request.amount().currency()));
            return withCard(ProviderPaymentResult.succeeded(request.providerReference(), amount(captured), "captured"), captured);
        } catch (RazorpayApi.BadRequest e) {
            JsonNode current = api.get(account, "/payments/" + text(found, "id"), Map.of());
            if ("captured".equals(text(current, "status"))) {
                return withCard(ProviderPaymentResult.succeeded(request.providerReference(), amount(current), "captured"), current);
            }
            return ProviderPaymentResult.failed(request.providerReference(), failure(e), "capture_rejected");
        }
    }

    @Override
    public ProviderPaymentResult voidAuthorization(MerchantAccount account, VoidRequest request) {
        throw new UnsupportedOperationException("Razorpay cannot void an authorization; it lapses at the issuer");
    }

    // ------------------------------------------------------------------ refunds

    @Override
    public ProviderRefundResult refund(MerchantAccount account, RefundRequest request) {
        Optional<String> paymentId = settledOrAuthorizedPayment(account, request.paymentProviderReference())
                .map(payment -> text(payment, "id"));
        if (paymentId.isEmpty()) {
            return ProviderRefundResult.failed(null, new ProviderFailure("payment_not_found",
                    "Razorpay has no captured payment for this attempt", FailureCategory.PROVIDER));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amount().amount());
        body.put("speed", "normal");
        body.put("receipt", request.refundId());
        body.put("notes", Map.of(REFUND_NOTE, request.refundId(), ATTEMPT_NOTE, request.attemptId()));
        try {
            return refundResult(api.post(account, "/payments/" + paymentId.get() + "/refund", body));
        } catch (RazorpayApi.BadRequest e) {
            if (e.isDuplicate()) {
                return findRefund(account, paymentId.get(), request.refundId()).map(RazorpayPaymentProvider::refundResult)
                        .orElseThrow(() -> new IllegalStateException("Razorpay reports a duplicate refund it cannot find", e));
            }
            return ProviderRefundResult.failed(null, failure(e));
        }
    }

    @Override
    public ProviderRefundResult fetchRefundStatus(MerchantAccount account, RefundStatusQuery query) {
        try {
            if (query.providerRefundReference() != null) {
                return refundResult(api.get(account, "/refunds/" + query.providerRefundReference(), Map.of()));
            }
            Optional<String> paymentId = settledOrAuthorizedPayment(account, query.paymentProviderReference())
                    .map(payment -> text(payment, "id"));
            return paymentId.flatMap(id -> findRefund(account, id, query.refundId()))
                    .map(RazorpayPaymentProvider::refundResult)
                    .orElseGet(ProviderRefundResult::notFound);
        } catch (RazorpayApi.BadRequest e) {
            if (e.isNotFound()) {
                return ProviderRefundResult.notFound();
            }
            throw e;
        }
    }

    private Optional<JsonNode> findRefund(MerchantAccount account, String paymentId, String refundId) {
        for (JsonNode refund : api.get(account, "/payments/" + paymentId + "/refunds", Map.of("count", "100")).path("items")) {
            if (refundId.equals(text(refund, "receipt")) || refundId.equals(text(refund.path("notes"), REFUND_NOTE))) {
                return Optional.of(refund);
            }
        }
        return Optional.empty();
    }

    private static ProviderRefundResult refundResult(JsonNode refund) {
        String id = text(refund, "id");
        Money amount = amount(refund);
        return switch (status(refund)) {
            case "processed" -> ProviderRefundResult.succeeded(id, amount);
            case "failed" -> ProviderRefundResult.failed(id, new ProviderFailure("refund_failed",
                    "Razorpay could not process the refund", FailureCategory.PROVIDER));
            default -> ProviderRefundResult.pending(id, amount);
        };
    }

    /** The Razorpay payment behind an order or Payment Link that is authorized or already captured. */
    private Optional<JsonNode> settledOrAuthorizedPayment(MerchantAccount account, String reference) {
        if (reference == null) {
            return Optional.empty();
        }
        String orderId = reference;
        if (reference.startsWith("plink_")) {
            orderId = text(api.get(account, "/payment_links/" + reference, Map.of()), "order_id");
            if (orderId == null) {
                return Optional.empty();
            }
        }
        for (JsonNode payment : api.get(account, "/orders/" + orderId + "/payments", Map.of()).path("items")) {
            String status = text(payment, "status");
            if ("captured".equals(status) || "authorized".equals(status) || "refunded".equals(status)) {
                return Optional.of(payment);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ webhooks

    @Override
    public List<ProviderEvent> parseWebhook(MerchantAccount account, InboundWebhook webhook) {
        if (account == null) {
            throw new WebhookVerificationException("Razorpay webhooks are signed per merchant account; use the account endpoint");
        }
        String secret = account.credential(WEBHOOK_SECRET)
                .orElseThrow(() -> new WebhookVerificationException("no webhook secret configured"));
        String signature = webhook.header("X-Razorpay-Signature");
        if (signature == null || !Hashing.constantTimeEquals(Hashing.hmacSha256Hex(secret, webhook.body()), signature)) {
            throw new WebhookVerificationException("signature mismatch");
        }
        String eventId = webhook.header("X-Razorpay-Event-Id");
        if (eventId == null || eventId.isBlank()) {
            throw new WebhookVerificationException("missing X-Razorpay-Event-Id");
        }
        JsonNode event = parse(webhook.body());
        String type = text(event, "event");
        JsonNode payload = event.path("payload");
        JsonNode payment = payload.path("payment").path("entity");
        if (type == null) {
            return List.of();
        }
        if (type.startsWith("payment.dispute.")) {
            return List.of(disputeEvent(eventId, type, payload.path("dispute").path("entity"), payment));
        }
        if (type.startsWith("refund.")) {
            JsonNode refund = payload.path("refund").path("entity");
            String merchantReference = Optional.ofNullable(text(refund, "receipt"))
                    .orElse(text(refund.path("notes"), REFUND_NOTE));
            return List.of(ProviderEvent.refund(eventId, type, text(refund, "id"), merchantReference, refundResult(refund)));
        }
        if (type.startsWith("payment_link.")) {
            JsonNode link = payload.path("payment_link").path("entity");
            String linkId = text(link, "id");
            ProviderPaymentResult result = switch (type) {
                case "payment_link.paid" -> withCard(ProviderPaymentResult.succeeded(linkId, amount(payment), "paid"), payment);
                case "payment_link.expired", "payment_link.cancelled" -> ProviderPaymentResult.failed(linkId,
                        new ProviderFailure(type.replace('.', '_'), "The customer did not pay before the payment page closed",
                                FailureCategory.CUSTOMER), text(link, "status"));
                default -> null;
            };
            return result == null ? List.of()
                    : List.of(ProviderEvent.payment(eventId, type, linkId, text(link, "reference_id"), result));
        }
        String reference = text(payment, "order_id");
        String attemptId = text(payment.path("notes"), ATTEMPT_NOTE);
        ProviderPaymentResult result = switch (type) {
            case "payment.captured", "order.paid" -> withCard(ProviderPaymentResult.succeeded(reference, amount(payment), "captured"), payment);
            case "payment.authorized" -> withCard(ProviderPaymentResult.authorized(reference, amount(payment), "authorized"), payment);
            // Not final: Razorpay can still capture it (see fromPayments).
            case "payment.failed" -> ProviderPaymentResult.pending(reference, "failed_retryable");
            default -> null;
        };
        return result == null ? List.of() : List.of(ProviderEvent.payment(eventId, type, reference, attemptId, result));
    }

    private static ProviderEvent disputeEvent(String eventId, String type, JsonNode dispute, JsonNode payment) {
        ProviderDisputeResult.Status status = switch (status(dispute)) {
            case "under_review" -> ProviderDisputeResult.Status.UNDER_REVIEW;
            case "won" -> ProviderDisputeResult.Status.WON;
            case "lost" -> ProviderDisputeResult.Status.LOST;
            // Closed without a decision: lost if Razorpay kept the money, otherwise won.
            case "closed" -> dispute.path("amount_deducted").asLong(0) > 0
                    ? ProviderDisputeResult.Status.LOST : ProviderDisputeResult.Status.WON;
            default -> ProviderDisputeResult.Status.OPEN;
        };
        long respondBy = dispute.path("respond_by").asLong(0);
        ProviderDisputeResult result = new ProviderDisputeResult(text(dispute, "id"), text(payment, "order_id"), status,
                amount(dispute), text(dispute, "reason_code"), respondBy > 0 ? Instant.ofEpochSecond(respondBy) : null,
                text(dispute, "status"));
        return ProviderEvent.dispute(eventId, type, text(payment.path("notes"), ATTEMPT_NOTE), result);
    }

    // ------------------------------------------------------------------ lookups and mapping

    private Optional<JsonNode> findOrderByReceipt(MerchantAccount account, String receipt) {
        for (JsonNode order : api.get(account, "/orders", Map.of("receipt", receipt)).path("items")) {
            if (receipt.equals(text(order, "receipt"))) {
                return Optional.of(order);
            }
        }
        return Optional.empty();
    }

    private Optional<JsonNode> findLinkByReference(MerchantAccount account, String referenceId) {
        for (JsonNode link : api.get(account, "/payment_links", Map.of("reference_id", referenceId)).path("payment_links")) {
            if (referenceId.equals(text(link, "reference_id"))) {
                return Optional.of(link);
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> notes(InitiatePaymentRequest request) {
        return Map.of(ATTEMPT_NOTE, request.attemptId(), "pg_merchant_id", request.merchantId());
    }

    private static Money amount(JsonNode entity) {
        String currency = text(entity, "currency");
        return entity.has("amount") && currency != null ? Money.of(entity.path("amount").asLong(), currency) : null;
    }

    private static ProviderPaymentResult withCard(ProviderPaymentResult result, JsonNode payment) {
        JsonNode card = payment.path("card");
        String network = text(card, "network");
        String last4 = text(card, "last4");
        if (network == null || last4 == null) {
            return result;
        }
        String normalized = network.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "_").replaceAll("^_|_$", "");
        String digits = last4.length() < 4 ? "0".repeat(4 - last4.length()) + last4 : last4;
        try {
            return result.withCard(new CardDetails(normalized, digits));
        } catch (IllegalArgumentException e) {
            return result;
        }
    }

    private static ProviderFailure paymentFailure(JsonNode payment) {
        String reason = Optional.ofNullable(text(payment, "error_reason")).orElse("payment_failed");
        String message = Optional.ofNullable(text(payment, "error_description")).orElse("Payment failed");
        return new ProviderFailure(reason, message, category(text(payment, "error_source")));
    }

    private static ProviderFailure failure(RazorpayApi.BadRequest e) {
        return new ProviderFailure(Optional.ofNullable(e.reason()).orElse(e.code().toLowerCase(Locale.ROOT)),
                e.getMessage(), FailureCategory.VALIDATION);
    }

    private static FailureCategory category(String errorSource) {
        return switch (errorSource == null ? "" : errorSource) {
            case "customer" -> FailureCategory.CUSTOMER;
            case "issuer", "bank" -> FailureCategory.ISSUER;
            case "business" -> FailureCategory.VALIDATION;
            default -> FailureCategory.PROVIDER;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.isObject() || value.isArray() ? null : value.asString();
    }

    private static String status(JsonNode entity) {
        String status = text(entity, "status");
        return status == null ? "" : status;
    }

    private static <V> void putIfPresent(Map<String, V> map, String key, V value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private JsonNode parse(String body) {
        try {
            return api.parse(body);
        } catch (RuntimeException e) {
            throw new WebhookVerificationException("malformed JSON");
        }
    }
}
