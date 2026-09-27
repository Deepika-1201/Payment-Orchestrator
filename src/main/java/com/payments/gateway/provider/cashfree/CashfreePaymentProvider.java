package com.payments.gateway.provider.cashfree;

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
import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.UpiFlow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;

/**
 * Cashfree adapter (ADR-031), built from Cashfree's PG API reference.
 * <ul>
 *   <li>By default the customer pays on a Cashfree-hosted Payment Link whose {@code link_id} is our attempt id. The
 *       attempt's provider reference is {@code cflink_<cf_link_id>}, because Cashfree reports link payments on orders it
 *       creates itself ({@code CFPay_...}) and tags them only with {@code cf_link_id}.</li>
 *   <li>With {@code upi-s2s}, UPI intent and QR use Create Order ({@code order_id} = attempt id) and Order Pay.</li>
 *   <li>Refunds are keyed by our refund id; amounts are sent in rupees and read back into paise exactly.</li>
 *   <li>Webhooks are signed with the merchant's client secret: Base64(HMAC-SHA256(timestamp + raw body)).</li>
 * </ul>
 */
public final class CashfreePaymentProvider implements PaymentProvider {

    static final String CLIENT_ID = "client_id";
    static final String CLIENT_SECRET = "client_secret";
    static final String LINK_REFERENCE_PREFIX = "cflink_";

    private static final List<CredentialField> CREDENTIALS = List.of(
            new CredentialField(CLIENT_ID, false, true),
            new CredentialField(CLIENT_SECRET, true, true));
    private static final long UPI_MAX = 10_000_000L;
    private static final long CARD_MAX = 100_000_000L;
    private static final Duration MIN_LINK_TTL = Duration.ofMinutes(16);

    private final CashfreeProperties properties;
    private final CashfreeApi api;
    private final Clock clock;
    private final ProviderCapabilities capabilities;

    CashfreePaymentProvider(CashfreeProperties properties, CashfreeApi api, Clock clock) {
        this.properties = properties;
        this.api = api;
        this.clock = clock;
        Set<UpiFlow> upiFlows = properties.upiS2s() ? EnumSet.of(UpiFlow.INTENT, UpiFlow.QR) : EnumSet.of(UpiFlow.INTENT);
        this.capabilities = new ProviderCapabilities(Map.of(
                MethodType.UPI, new MethodSupport(upiFlows, 100, UPI_MAX, false),
                MethodType.CARD, new MethodSupport(Set.of(), 100, CARD_MAX, false),
                MethodType.NETBANKING, new MethodSupport(Set.of(), 100, CARD_MAX, false)),
                Set.of("INR"), false, true, false, true);
    }

    @Override
    public String code() {
        return CashfreeApi.CODE;
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
        Optional<String> phone = indianMobile(request.customerPhone());
        if (phone.isEmpty()) {
            // Routing skips Cashfree without a phone (requiresCustomerPhone); a number Cashfree would reject lands here.
            return ProviderPaymentResult.failed(null, new ProviderFailure("customer_phone_invalid",
                    "Cashfree needs a 10-digit Indian mobile number for the customer", FailureCategory.VALIDATION), "not_sent");
        }
        boolean s2s = request.method().type() == MethodType.UPI && properties.upiS2s();
        return s2s ? initiateUpiS2s(account, request, phone.get()) : initiateLink(account, request, phone.get());
    }

    private ProviderPaymentResult initiateLink(MerchantAccount account, InitiatePaymentRequest request, String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("link_id", request.attemptId());
        body.put("link_amount", rupees(request.amount()));
        body.put("link_currency", request.amount().currency());
        body.put("link_purpose", Optional.ofNullable(request.description()).filter(d -> !d.isBlank()).orElse("Payment"));
        Map<String, String> customer = new LinkedHashMap<>();
        customer.put("customer_phone", phone);
        if (request.customerEmail() != null) {
            customer.put("customer_email", request.customerEmail());
        }
        body.put("customer_details", customer);
        Duration ttl = properties.hostedPageTtl().compareTo(MIN_LINK_TTL) < 0 ? MIN_LINK_TTL : properties.hostedPageTtl();
        body.put("link_expiry_time", timestamp(clock.instant().plus(ttl)));
        body.put("link_notify", Map.of("send_sms", false, "send_email", false));
        body.put("link_notes", Map.of("pg_attempt_id", request.attemptId()));
        if (request.returnUrl() != null && request.returnUrl().startsWith("https://")) {
            body.put("link_meta", Map.of("return_url", request.returnUrl()));
        }
        JsonNode link;
        try {
            link = api.post(account, "/links", body);
        } catch (CashfreeApi.ApiError e) {
            if (!e.isDuplicate()) {
                return ProviderPaymentResult.failed(null, failure(e), "link_rejected");
            }
            link = api.get(account, "/links/" + CashfreeApi.segment(request.attemptId()));
        }
        return linkResult(account, link);
    }

    private ProviderPaymentResult initiateUpiS2s(MerchantAccount account, InitiatePaymentRequest request, String phone) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("order_id", request.attemptId());
        order.put("order_amount", rupees(request.amount()));
        order.put("order_currency", request.amount().currency());
        order.put("customer_details", Map.of("customer_id", customerId(request), "customer_phone", phone));
        order.put("order_expiry_time", timestamp(clock.instant().plus(properties.hostedPageTtl().compareTo(MIN_LINK_TTL) < 0
                ? MIN_LINK_TTL : properties.hostedPageTtl())));
        order.put("order_tags", Map.of("pg_attempt_id", request.attemptId()));
        JsonNode created;
        try {
            created = api.post(account, "/orders", order);
        } catch (CashfreeApi.ApiError e) {
            if (!e.isDuplicate()) {
                return ProviderPaymentResult.failed(null, failure(e), "order_rejected");
            }
            created = api.get(account, "/orders/" + CashfreeApi.segment(request.attemptId()));
        }
        String orderId = request.attemptId();
        if (!"ACTIVE".equals(text(created, "order_status"))) {
            return orderResult(account, orderId, created);
        }
        String channel = request.method().upiFlow() == UpiFlow.QR ? "qrcode" : "link";
        JsonNode paid;
        try {
            paid = api.post(account, "/orders/sessions", Map.of(
                    "payment_session_id", text(created, "payment_session_id"),
                    "payment_method", Map.of("upi", Map.of("channel", channel))));
        } catch (CashfreeApi.ApiError e) {
            return ProviderPaymentResult.failed(orderId, failure(e), "order_pay_rejected");
        }
        JsonNode payload = paid.path("data").path("payload");
        if (channel.equals("qrcode")) {
            String qr = text(payload, "qrcode");
            return qr == null ? ProviderPaymentResult.pending(orderId, "order_pay")
                    : ProviderPaymentResult.requiresAction(orderId,
                            NextAction.displayQr(qr, clock.instant().plus(properties.hostedPageTtl())), "order_pay");
        }
        String intent = text(payload, "default");
        return intent == null ? ProviderPaymentResult.pending(orderId, "order_pay")
                : ProviderPaymentResult.requiresAction(orderId, NextAction.upiIntent(intent), "order_pay");
    }

    @Override
    public ProviderPaymentResult fetchPaymentStatus(MerchantAccount account, PaymentStatusQuery query) {
        String reference = query.providerReference();
        String id = CashfreeApi.segment(query.attemptId());
        try {
            if (reference == null) {
                // Created before a timeout? Our attempt id is the link_id or the order_id.
                try {
                    return linkResult(account, api.get(account, "/links/" + id));
                } catch (CashfreeApi.ApiError e) {
                    if (!e.isNotFound()) {
                        throw e;
                    }
                }
                return orderResult(account, query.attemptId(), api.get(account, "/orders/" + id));
            }
            if (reference.startsWith(LINK_REFERENCE_PREFIX)) {
                return linkResult(account, api.get(account, "/links/" + id));
            }
            return orderResult(account, reference, api.get(account, "/orders/" + CashfreeApi.segment(reference)));
        } catch (CashfreeApi.ApiError e) {
            if (e.isNotFound()) {
                return ProviderPaymentResult.notFound();
            }
            throw e;
        }
    }

    private ProviderPaymentResult linkResult(MerchantAccount account, JsonNode link) {
        String reference = LINK_REFERENCE_PREFIX + text(link, "cf_link_id");
        String status = Optional.ofNullable(text(link, "link_status")).orElse("");
        switch (status) {
            case "PAID" -> {
                return linkPayment(account, link).map(payment -> withCard(ProviderPaymentResult.succeeded(reference,
                                amount(payment, "payment_amount", "payment_currency"), "PAID"), payment))
                        .orElseGet(() -> ProviderPaymentResult.succeeded(reference,
                                amount(link, "link_amount_paid", "link_currency"), "PAID"));
            }
            case "EXPIRED", "CANCELLED" -> {
                // A payment can complete just as the link closes; only an unpaid link is a failure.
                Optional<JsonNode> paid = linkPayment(account, link);
                if (paid.isPresent()) {
                    return withCard(ProviderPaymentResult.succeeded(reference,
                            amount(paid.get(), "payment_amount", "payment_currency"), status), paid.get());
                }
                return ProviderPaymentResult.failed(reference, new ProviderFailure("payment_link_" + status.toLowerCase(Locale.ROOT),
                        "The customer did not pay before the payment page closed", FailureCategory.CUSTOMER), status);
            }
            default -> {
                String url = text(link, "link_url");
                return ProviderPaymentResult.requiresAction(reference, url == null ? null : NextAction.redirect(url), status);
            }
        }
    }

    /** The successful payment on any order Cashfree created for the link. */
    private Optional<JsonNode> linkPayment(MerchantAccount account, JsonNode link) {
        for (JsonNode order : api.get(account, "/links/" + CashfreeApi.segment(text(link, "link_id")) + "/orders")) {
            if ("PAID".equals(text(order, "order_status"))) {
                for (JsonNode payment : api.get(account, "/orders/" + CashfreeApi.segment(text(order, "order_id")) + "/payments")) {
                    if ("SUCCESS".equals(text(payment, "payment_status"))) {
                        return Optional.of(payment);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private ProviderPaymentResult orderResult(MerchantAccount account, String orderId, JsonNode order) {
        String status = Optional.ofNullable(text(order, "order_status")).orElse("");
        JsonNode payments = api.get(account, "/orders/" + CashfreeApi.segment(orderId) + "/payments");
        JsonNode lastFailed = null;
        for (JsonNode payment : payments) {
            switch (Optional.ofNullable(text(payment, "payment_status")).orElse("")) {
                case "SUCCESS" -> {
                    return withCard(ProviderPaymentResult.succeeded(orderId,
                            amount(payment, "payment_amount", "payment_currency"), "SUCCESS"), payment);
                }
                case "FAILED", "USER_DROPPED", "CANCELLED", "VOID" -> lastFailed = payment;
                default -> {
                }
            }
        }
        // The customer can retry on an ACTIVE order, so a failed payment is final only once the order has closed.
        if (status.equals("EXPIRED") || status.equals("TERMINATED")) {
            return ProviderPaymentResult.failed(orderId, lastFailed == null
                    ? new ProviderFailure("order_" + status.toLowerCase(Locale.ROOT), "The customer did not pay in time",
                    FailureCategory.CUSTOMER)
                    : paymentFailure(lastFailed), status);
        }
        return lastFailed != null ? ProviderPaymentResult.pending(orderId, "failed_retryable")
                : ProviderPaymentResult.requiresAction(orderId, null, status);
    }

    @Override
    public ProviderPaymentResult capture(MerchantAccount account, CaptureRequest request) {
        // Payments are captured automatically (no manual-capture capability), so capture only confirms the outcome.
        return fetchPaymentStatus(account, new PaymentStatusQuery(request.attemptId(), request.providerReference()));
    }

    @Override
    public ProviderPaymentResult voidAuthorization(MerchantAccount account, VoidRequest request) {
        throw new UnsupportedOperationException("Cashfree voids only pre-authorizations, which this adapter does not create");
    }

    // ------------------------------------------------------------------ refunds

    @Override
    public ProviderRefundResult refund(MerchantAccount account, RefundRequest request) {
        Optional<String> orderId = paidOrderId(account, request.attemptId(), request.paymentProviderReference());
        if (orderId.isEmpty()) {
            return ProviderRefundResult.failed(null, new ProviderFailure("payment_not_found",
                    "Cashfree has no successful payment for this attempt", FailureCategory.PROVIDER));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refund_id", request.refundId());
        body.put("refund_amount", rupees(request.amount()));
        body.put("refund_note", Optional.ofNullable(request.reason()).filter(r -> r.length() >= 3)
                .map(r -> r.length() > 100 ? r.substring(0, 100) : r).orElse("Refund"));
        body.put("refund_speed", "STANDARD");
        String path = "/orders/" + CashfreeApi.segment(orderId.get()) + "/refunds";
        try {
            return refundResult(api.post(account, path, body));
        } catch (CashfreeApi.ApiError e) {
            if (e.isDuplicate()) {
                return refundResult(api.get(account, path + "/" + CashfreeApi.segment(request.refundId())));
            }
            return ProviderRefundResult.failed(null, failure(e));
        }
    }

    @Override
    public ProviderRefundResult fetchRefundStatus(MerchantAccount account, RefundStatusQuery query) {
        try {
            Optional<String> orderId = paidOrderId(account, query.attemptId(), query.paymentProviderReference());
            if (orderId.isEmpty()) {
                return ProviderRefundResult.notFound();
            }
            return refundResult(api.get(account, "/orders/" + CashfreeApi.segment(orderId.get()) + "/refunds/"
                    + CashfreeApi.segment(query.refundId())));
        } catch (CashfreeApi.ApiError e) {
            if (e.isNotFound()) {
                return ProviderRefundResult.notFound();
            }
            throw e;
        }
    }

    /** Refunds belong to the order that was paid: ours for S2S, Cashfree's CFPay_... order for a link. */
    private Optional<String> paidOrderId(MerchantAccount account, String attemptId, String reference) {
        if (reference == null || !reference.startsWith(LINK_REFERENCE_PREFIX)) {
            return Optional.ofNullable(attemptId);
        }
        for (JsonNode order : api.get(account, "/links/" + CashfreeApi.segment(attemptId) + "/orders")) {
            if ("PAID".equals(text(order, "order_status"))) {
                return Optional.ofNullable(text(order, "order_id"));
            }
        }
        return Optional.empty();
    }

    private static ProviderRefundResult refundResult(JsonNode refund) {
        String id = text(refund, "cf_refund_id");
        Money amount = amount(refund, "refund_amount", "refund_currency");
        return switch (Optional.ofNullable(text(refund, "refund_status")).orElse("")) {
            case "SUCCESS" -> ProviderRefundResult.succeeded(id, amount);
            case "CANCELLED", "REJECTED" -> ProviderRefundResult.failed(id, new ProviderFailure("refund_"
                    + text(refund, "refund_status").toLowerCase(Locale.ROOT),
                    Optional.ofNullable(text(refund, "status_description")).orElse("Cashfree could not process the refund"),
                    FailureCategory.PROVIDER));
            default -> ProviderRefundResult.pending(id, amount);
        };
    }

    // ------------------------------------------------------------------ webhooks

    @Override
    public List<ProviderEvent> parseWebhook(MerchantAccount account, InboundWebhook webhook) {
        if (account == null) {
            throw new WebhookVerificationException("Cashfree webhooks are signed per merchant account; use the account endpoint");
        }
        String secret = account.credential(CLIENT_SECRET)
                .orElseThrow(() -> new WebhookVerificationException("no client secret configured"));
        String timestamp = webhook.header("x-webhook-timestamp");
        String signature = webhook.header("x-webhook-signature");
        if (timestamp == null || signature == null
                || !Hashing.constantTimeEquals(signature(secret, timestamp, webhook.body()), signature)) {
            throw new WebhookVerificationException("signature mismatch");
        }
        // No freshness window: Cashfree retries for hours and operators batch-resend from its dashboard, and it does not
        // document re-signing those with a new timestamp. A replayed body has the same id below and is deduplicated
        // (per account) for the inbox retention; after that it can only restate a status that was true.
        // Dedupe on the signed content: the unsigned x-idempotency-key header could be altered on a replay.
        String eventId = "sha256:" + HexFormat.of().formatHex(Hashing.sha256(webhook.body()));
        JsonNode event = parse(webhook.body());
        String type = Optional.ofNullable(text(event, "type")).orElse("");
        JsonNode data = event.path("data");
        return switch (type) {
            case "PAYMENT_SUCCESS_WEBHOOK", "PAYMENT_FAILED_WEBHOOK", "PAYMENT_USER_DROPPED_WEBHOOK" ->
                    paymentEvent(eventId, type, data);
            case "REFUND_STATUS_WEBHOOK" -> {
                JsonNode refund = data.path("refund");
                yield List.of(ProviderEvent.refund(eventId, type, text(refund, "cf_refund_id"), text(refund, "refund_id"),
                        refundResult(refund)));
            }
            case "DISPUTE_CREATED", "DISPUTE_UPDATED", "DISPUTE_CLOSED" -> disputeEvent(account, eventId, type, data);
            default -> List.of();
        };
    }

    private static List<ProviderEvent> paymentEvent(String eventId, String type, JsonNode data) {
        JsonNode order = data.path("order");
        JsonNode payment = data.path("payment");
        String linkId = text(order.path("order_tags"), "cf_link_id");
        String orderId = text(order, "order_id");
        String reference = linkId != null ? LINK_REFERENCE_PREFIX + linkId : orderId;
        String attemptId = linkId != null ? null : orderId;
        ProviderPaymentResult result = switch (type) {
            case "PAYMENT_SUCCESS_WEBHOOK" -> withCard(ProviderPaymentResult.succeeded(reference,
                    amount(payment, "payment_amount", "payment_currency"), "SUCCESS"), payment);
            // Not final: the customer can pay again on the same order or link until it closes (see orderResult).
            default -> ProviderPaymentResult.pending(reference, "failed_retryable");
        };
        return List.of(ProviderEvent.payment(eventId, type, reference, attemptId, result));
    }

    private List<ProviderEvent> disputeEvent(MerchantAccount account, String eventId, String type, JsonNode data) {
        JsonNode dispute = data.path("dispute");
        String disputeType = Optional.ofNullable(text(dispute, "dispute_type")).orElse("");
        if (disputeType.equals("RETRIEVAL")) {
            return List.of(); // A request for documents: no funds move.
        }
        String orderId = text(data.path("order_details"), "order_id");
        // Link payments are disputed on Cashfree's CFPay_... order; its tags lead back to the link and our attempt.
        String reference = orderId;
        String attemptId = orderId;
        if (orderId != null && orderId.startsWith("CFPay_")) {
            String linkId = text(api.get(account, "/orders/" + CashfreeApi.segment(orderId)).path("order_tags"), "cf_link_id");
            reference = linkId == null ? null : LINK_REFERENCE_PREFIX + linkId;
            attemptId = null;
        }
        String status = Optional.ofNullable(text(dispute, "dispute_status")).orElse("");
        ProviderDisputeResult.Status mapped;
        if (status.endsWith("_MERCHANT_WON")) {
            mapped = ProviderDisputeResult.Status.WON;
        } else if (status.endsWith("_MERCHANT_LOST") || status.endsWith("_MERCHANT_ACCEPTED")
                || status.endsWith("_INSUFFICIENT_EVIDENCE")) {
            mapped = ProviderDisputeResult.Status.LOST;
        } else if (status.endsWith("_DOCS_RECEIVED") || status.endsWith("_UNDER_REVIEW")) {
            mapped = ProviderDisputeResult.Status.UNDER_REVIEW;
        } else {
            mapped = ProviderDisputeResult.Status.OPEN;
        }
        ProviderDisputeResult result = new ProviderDisputeResult(text(dispute, "dispute_id"), reference, mapped,
                amount(dispute, "dispute_amount", "dispute_amount_currency"),
                Optional.ofNullable(text(dispute, "reason_description")).orElse(text(dispute, "reason_code")),
                instant(text(dispute, "respond_by")), status);
        return List.of(ProviderEvent.dispute(eventId, type, attemptId, result));
    }

    static String signature(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal((timestamp + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    // ------------------------------------------------------------------ mapping helpers

    /** Cashfree amounts are rupees with up to two decimals. */
    static BigDecimal rupees(Money money) {
        return BigDecimal.valueOf(money.amount(), 2);
    }

    private static Money amount(JsonNode entity, String amountField, String currencyField) {
        JsonNode value = entity.path(amountField);
        String currency = text(entity, currencyField);
        if (value.isMissingNode() || value.isNull() || currency == null) {
            return null;
        }
        try {
            long paise = new BigDecimal(value.asString()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            return Money.of(paise, currency);
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** Cashfree accepts 10-digit Indian mobile numbers; +91/0 prefixes and separators are removed. */
    static Optional<String> indianMobile(String phone) {
        if (phone == null) {
            return Optional.empty();
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits.matches("[6-9][0-9]{9}") ? Optional.of(digits) : Optional.empty();
    }

    /** Stable per attempt, alphanumeric as Cashfree requires; not the merchant's customer reference (PII). */
    private static String customerId(InitiatePaymentRequest request) {
        return "c" + HexFormat.of().formatHex(Hashing.sha256(request.merchantId() + ":" + request.attemptId())).substring(0, 32);
    }

    private static ProviderPaymentResult withCard(ProviderPaymentResult result, JsonNode payment) {
        JsonNode card = payment.path("payment_method").path("card");
        String network = text(card, "card_network");
        String number = text(card, "card_number");
        if (network == null || number == null || number.length() < 4) {
            return result;
        }
        String last4 = number.substring(number.length() - 4);
        if (!last4.matches("[0-9]{4}")) {
            return result;
        }
        try {
            return result.withCard(new CardDetails(network.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "_"), last4));
        } catch (IllegalArgumentException e) {
            return result;
        }
    }

    private static ProviderFailure paymentFailure(JsonNode payment) {
        JsonNode error = payment.path("error_details");
        String reason = Optional.ofNullable(text(error, "error_reason")).orElse("payment_failed");
        String message = Optional.ofNullable(text(error, "error_description")).orElse("Payment failed");
        FailureCategory category = switch (Optional.ofNullable(text(error, "error_source")).orElse("")) {
            case "customer" -> FailureCategory.CUSTOMER;
            case "bank" -> FailureCategory.ISSUER;
            default -> FailureCategory.PROVIDER;
        };
        return new ProviderFailure(reason, message, category);
    }

    private static ProviderFailure failure(CashfreeApi.ApiError e) {
        return new ProviderFailure(e.code(), e.getMessage(), FailureCategory.VALIDATION);
    }

    private static String timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC).toString();
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.isObject() || value.isArray() ? null : value.asString();
    }

    private JsonNode parse(String body) {
        try {
            return api.parse(body);
        } catch (RuntimeException e) {
            throw new WebhookVerificationException("malformed JSON");
        }
    }
}
