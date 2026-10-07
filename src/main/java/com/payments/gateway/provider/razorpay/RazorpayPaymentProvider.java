package com.payments.gateway.provider.razorpay;

import com.payments.gateway.provider.spi.CredentialField;
import com.payments.gateway.provider.spi.InboundWebhook;
import com.payments.gateway.provider.spi.InitiatePaymentRequest;
import com.payments.gateway.provider.spi.MandateRequests.CreateMandateRequest;
import com.payments.gateway.provider.spi.MandateRequests.DebitNotificationQuery;
import com.payments.gateway.provider.spi.MandateRequests.DebitNotificationRequest;
import com.payments.gateway.provider.spi.MandateRequests.ExecuteDebitRequest;
import com.payments.gateway.provider.spi.MandateRequests.MandateQuery;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.provider.spi.ProviderCapabilities;
import com.payments.gateway.provider.spi.ProviderCapabilities.MandateSupport;
import com.payments.gateway.provider.spi.ProviderCapabilities.MethodSupport;
import com.payments.gateway.provider.spi.ProviderDisputeResult;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderFailure;
import com.payments.gateway.provider.spi.ProviderMandateResult;
import com.payments.gateway.provider.spi.ProviderNotificationResult;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.provider.spi.ProviderRequests.VoidRequest;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.provider.spi.SettlementReport.LineType;
import com.payments.gateway.provider.spi.WebhookVerificationException;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.UpiFlow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Razorpay adapter (ADR-030), built from Razorpay's API reference.
 * <ul>
 *   <li>UPI intent and QR use server-to-server UPI ({@code /payments/create/upi}) when Razorpay has enabled it;
 *       otherwise, and for cards and netbanking, the customer pays on a Razorpay-hosted Payment Link (ADR-008).</li>
 *   <li>Our attempt and refund ids are the Razorpay {@code receipt}/{@code reference_id} and are copied into
 *       {@code notes}, so every object can be found again after a timeout and every webhook maps back to us.</li>
 *   <li>Payments are captured automatically; Razorpay cannot void an authorization, so neither can this adapter.</li>
 *   <li>Settlement reports come from Razorpay's settlement recon, read per settlement day (ADR-032).</li>
 *   <li>Mandates (ADR-035) register through a registration link (an invoice keyed by our mandate id); debits run on
 *       the token, each cycle on the order that carried its pre-debit notification.</li>
 * </ul>
 */
public final class RazorpayPaymentProvider implements PaymentProvider {

    static final String KEY_ID = "key_id";
    static final String KEY_SECRET = "key_secret";
    static final String WEBHOOK_SECRET = "webhook_secret";
    static final String ATTEMPT_NOTE = "pg_attempt_id";
    static final String REFUND_NOTE = "pg_refund_id";
    static final String MANDATE_NOTE = "pg_mandate_id";
    static final String DEBIT_NOTE = "pg_debit_id";

    private static final List<CredentialField> CREDENTIALS = List.of(
            new CredentialField(KEY_ID, false, true),
            new CredentialField(KEY_SECRET, true, true),
            new CredentialField(WEBHOOK_SECRET, true, true));
    private static final long UPI_MAX = 10_000_000L;
    private static final long CARD_MAX = 100_000_000L;
    /** ₹1 authorization for UPI and card; eNACH registers without a charge and debits up to ₹1 crore. */
    private static final Map<MandateInstrument, MandateSupport> MANDATES = Map.of(
            MandateInstrument.UPI_AUTOPAY, new MandateSupport(100, UPI_MAX),
            MandateInstrument.CARD, new MandateSupport(100, UPI_MAX),
            MandateInstrument.ENACH, new MandateSupport(0, 1_000_000_000L));
    // Razorpay rejects Payment Links that expire less than 15 minutes after creation.
    private static final Duration MIN_LINK_TTL = Duration.ofMinutes(16);
    // Razorpay dates settlements in India time; recon pages hold at most 1000 items.
    private static final ZoneId SETTLEMENT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int RECON_PAGE = 1000;
    private static final int MAX_RECON_PAGES = 1000;

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
                Set.of("INR"), false, true, true).withMandates(properties.mandates() ? MANDATES : Map.of());
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
        return createOrFindOrder(account, body, request.attemptId());
    }

    /** Razorpay treats an order's receipt as an idempotency key: a repeat is answered with the existing order. */
    private JsonNode createOrFindOrder(MerchantAccount account, Map<String, Object> body, String receipt) {
        try {
            return api.post(account, "/orders", body);
        } catch (RazorpayApi.BadRequest e) {
            if (e.isDuplicate()) {
                return findOrderByReceipt(account, receipt)
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

    // ------------------------------------------------------------------ settlement reports

    @Override
    public Duration settlementLag() {
        return properties.settlementLag();
    }

    /**
     * Razorpay's settlement recon lists what was settled on a day, so every India-time day the window touches is read,
     * plus one on either side in case Razorpay dates a settlement differently, and items are kept by {@code settled_at}.
     * Each payout is then read for its amount and status (ADR-032).
     */
    @Override
    public SettlementReport fetchSettlementReport(MerchantAccount account, SettlementReportQuery query) {
        List<SettlementReport.Line> lines = new ArrayList<>();
        Map<String, Payout> payouts = new LinkedHashMap<>();
        LocalDate last = LocalDate.ofInstant(query.to().minusNanos(1), SETTLEMENT_ZONE).plusDays(1);
        for (LocalDate day = LocalDate.ofInstant(query.from(), SETTLEMENT_ZONE).minusDays(1); !day.isAfter(last);
             day = day.plusDays(1)) {
            for (JsonNode item : reconItems(account, day)) {
                Instant settledAt = epochSeconds(item, "settled_at");
                String settlementId = text(item, "settlement_id");
                boolean unsettled = item.path("settled").isBoolean() && !item.path("settled").asBoolean();
                if (unsettled || settledAt == null || settlementId == null || settledAt.isBefore(query.from())
                        || !settledAt.isBefore(query.to())) {
                    continue;
                }
                SettlementReport.Line line = reconLine(account, item);
                lines.add(line);
                payouts.putIfAbsent(settlementId, new Payout(settledAt, line.amount().currency()));
            }
        }
        List<SettlementReport.Settlement> settlements = new ArrayList<>();
        payouts.forEach((id, payout) -> settlements.add(settlement(account, id, payout)));
        return new SettlementReport(lines, settlements);
    }

    private record Payout(Instant settledAt, String currency) {
    }

    private List<JsonNode> reconItems(MerchantAccount account, LocalDate day) {
        List<JsonNode> items = new ArrayList<>();
        for (int page = 0; page < MAX_RECON_PAGES; page++) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("year", String.valueOf(day.getYear()));
            params.put("month", String.format(Locale.ROOT, "%02d", day.getMonthValue()));
            params.put("day", String.format(Locale.ROOT, "%02d", day.getDayOfMonth()));
            params.put("count", String.valueOf(RECON_PAGE));
            params.put("skip", String.valueOf(page * RECON_PAGE));
            JsonNode found = report(() -> api.get(account, "/settlements/recon/combined", params)).path("items");
            found.forEach(items::add);
            if (found.size() < RECON_PAGE) {
                return items;
            }
        }
        throw new ProviderUnavailableException(RazorpayApi.CODE, "settlement recon for " + day + " has more than "
                + MAX_RECON_PAGES * RECON_PAGE + " items");
    }

    /**
     * The fee is what Razorpay kept: the gap between the amount and what it credited or debited, so fees invoiced
     * separately count as zero. Adjustments without a dispute have nothing of ours to match.
     */
    private SettlementReport.Line reconLine(MerchantAccount account, JsonNode item) {
        String id = text(item, "entity_id");
        String currency = Optional.ofNullable(text(item, "currency")).orElse("INR");
        Money amount = Money.of(item.path("amount").asLong(0), currency);
        long credit = item.path("credit").asLong(0);
        long debit = item.path("debit").asLong(0);
        Money keptOnCredit = Money.of(Math.max(0, amount.amount() - credit), currency);
        Money keptOnDebit = Money.of(Math.max(0, debit - amount.amount()), currency);
        String settlementId = text(item, "settlement_id");
        Instant createdAt = epochSeconds(item, "created_at");
        String type = Optional.ofNullable(text(item, "type")).orElse("");
        String disputeId = text(item, "dispute_id");
        if (type.equals("payment")) {
            // A mandate debit's order receipt is its notification id, so the payment's notes come first.
            String attemptId = Optional.ofNullable(note(item, ATTEMPT_NOTE)).orElse(text(item, "order_receipt"));
            return new SettlementReport.Line(id, LineType.PAYMENT, Optional.ofNullable(text(item, "order_id")).orElse(id),
                    attemptId, amount, keptOnCredit, settlementId, createdAt);
        }
        if (type.equals("refund")) {
            return new SettlementReport.Line(id, LineType.REFUND, id, note(item, REFUND_NOTE), amount, keptOnDebit,
                    settlementId, createdAt);
        }
        if (type.equals("adjustment") && disputeId != null) {
            boolean withheld = debit > 0;
            return new SettlementReport.Line(id, withheld ? LineType.CHARGEBACK : LineType.CHARGEBACK_REVERSAL, disputeId,
                    disputedAttempt(account, disputeId), amount, withheld ? keptOnDebit : keptOnCredit, settlementId,
                    createdAt);
        }
        String description = text(item, "description");
        boolean credited = credit > 0;
        return new SettlementReport.Line(id, credited ? LineType.ADJUSTMENT_CREDIT : LineType.ADJUSTMENT_DEBIT, id, null,
                Money.of(credited ? credit : debit, currency), Money.of(0, currency), settlementId, createdAt,
                description == null ? type : type + ": " + description);
    }

    /** The attempt behind a dispute, for a chargeback the gateway only learns about from the report (ADR-018). */
    private String disputedAttempt(MerchantAccount account, String disputeId) {
        try {
            String paymentId = text(api.get(account, "/disputes/" + RazorpayApi.segment(disputeId), Map.of()), "payment_id");
            if (paymentId == null) {
                return null;
            }
            JsonNode payment = api.get(account, "/payments/" + RazorpayApi.segment(paymentId), Map.of());
            String attemptId = text(payment.path("notes"), ATTEMPT_NOTE);
            String orderId = text(payment, "order_id");
            if (attemptId != null || orderId == null) {
                return attemptId;
            }
            return text(api.get(account, "/orders/" + RazorpayApi.segment(orderId), Map.of()), "receipt");
        } catch (RazorpayApi.BadRequest e) {
            return null;
        }
    }

    private SettlementReport.Settlement settlement(MerchantAccount account, String settlementId, Payout payout) {
        JsonNode settlement = report(() -> api.get(account, "/settlements/" + RazorpayApi.segment(settlementId), Map.of()));
        String status = Optional.ofNullable(text(settlement, "status")).orElse("");
        long amount = switch (status) {
            case "processed" -> settlement.path("amount").asLong();
            // Nothing reached the bank: the payout check flags the whole net and the receivable keeps it.
            case "failed" -> 0;
            default -> throw new ProviderUnavailableException(RazorpayApi.CODE, "settlement " + settlementId
                    + " has status '" + status + "'; reconcile this window again once Razorpay has paid it out");
        };
        return new SettlementReport.Settlement(settlementId, amount, payout.currency(), text(settlement, "utr"),
                payout.settledAt());
    }

    /** Report calls only read, so a refusal is reported with Razorpay's reason rather than as an adapter error. */
    private static JsonNode report(Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (RazorpayApi.BadRequest e) {
            throw new ProviderUnavailableException(RazorpayApi.CODE, "Razorpay refused a settlement report request: "
                    + e.getMessage());
        }
    }

    private static String note(JsonNode entity, String key) {
        JsonNode notes = entity.path("notes");
        return notes.isObject() ? text(notes, key) : null;
    }

    private static Instant epochSeconds(JsonNode entity, String field) {
        long seconds = entity.path(field).asLong(0);
        return seconds > 0 ? Instant.ofEpochSecond(seconds) : null;
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
        if (type.startsWith("token.")) {
            return tokenEvents(eventId, type, payload.path("token").path("entity"));
        }
        if (type.startsWith("invoice.")) {
            return invoiceEvents(eventId, type, payload.path("invoice").path("entity"), payment);
        }
        if (type.startsWith("order.notification.")) {
            return notificationEvents(eventId, type, payload.path("order").path("entity"), event);
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

    /** Token webhooks carry no receipt of ours: the mandate is found by its token. */
    private static List<ProviderEvent> tokenEvents(String eventId, String type, JsonNode token) {
        String tokenId = text(token, "id");
        ProviderMandateResult.Status status = switch (type) {
            case "token.confirmed", "token.resumed" -> ProviderMandateResult.Status.ACTIVE;
            case "token.paused" -> ProviderMandateResult.Status.PAUSED;
            case "token.cancelled" -> ProviderMandateResult.Status.REVOKED;
            case "token.rejected" -> ProviderMandateResult.Status.FAILED;
            default -> null;
        };
        if (status == null || tokenId == null) {
            return List.of();
        }
        return List.of(ProviderEvent.mandate(eventId, type, null, new ProviderMandateResult(status, null, tokenId,
                text(token, "customer_id"), null, null, rejection(status, token.path("recurring_details")), type)));
    }

    /**
     * A paid registration link yields its ₹1 charge's payment event and a mandate event with the token, which stays
     * {@code PENDING} until the token is confirmed. Distinct event ids keep both in the webhook inbox.
     */
    private static List<ProviderEvent> invoiceEvents(String eventId, String type, JsonNode invoice, JsonNode payment) {
        String invoiceId = text(invoice, "id");
        String receipt = text(invoice, "receipt");
        String customerId = text(invoice, "customer_id");
        String orderId = text(invoice, "order_id");
        if (invoiceId == null) {
            return List.of();
        }
        if (type.equals("invoice.expired")) {
            return List.of(ProviderEvent.mandate(eventId, type, receipt, new ProviderMandateResult(
                    ProviderMandateResult.Status.FAILED, invoiceId, null, customerId, orderId, null, registrationExpired(),
                    "expired")));
        }
        if (!type.equals("invoice.paid")) {
            return List.of();
        }
        List<ProviderEvent> events = new ArrayList<>();
        String paymentStatus = status(payment);
        if (orderId != null && payment.path("amount").asLong(0) > 0
                && (paymentStatus.equals("captured") || paymentStatus.equals("authorized"))) {
            ProviderPaymentResult charge = paymentStatus.equals("captured")
                    ? ProviderPaymentResult.succeeded(orderId, amount(payment), "captured")
                    : ProviderPaymentResult.authorized(orderId, amount(payment), "authorized");
            events.add(ProviderEvent.payment(eventId + "#payment", type, orderId, null, withCard(charge, payment)));
        }
        events.add(ProviderEvent.mandate(eventId + "#mandate", type, receipt, new ProviderMandateResult(
                ProviderMandateResult.Status.PENDING, invoiceId, text(payment, "token_id"), customerId, orderId, null, null,
                "paid")));
        return events;
    }

    private List<ProviderEvent> notificationEvents(String eventId, String type, JsonNode order, JsonNode event) {
        String orderId = text(order, "id");
        JsonNode notification = order.path("notification");
        ProviderNotificationResult result = switch (type) {
            case "order.notification.delivered" -> ProviderNotificationResult.delivered(orderId,
                    Optional.ofNullable(epochSeconds(notification, "delivered_at"))
                            .or(() -> Optional.ofNullable(epochSeconds(event, "created_at")))
                            .orElse(clock.instant()), "delivered");
            case "order.notification.failed" -> ProviderNotificationResult.failed(orderId, notificationFailure(notification),
                    "failed");
            default -> null;
        };
        return result == null || orderId == null ? List.of()
                : List.of(ProviderEvent.notification(eventId, type, text(order, "receipt"), result));
    }

    // ------------------------------------------------------------------ mandates (ADR-035, LLD §18.9)

    @Override
    public ProviderMandateResult createMandate(MerchantAccount account, CreateMandateRequest request) {
        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("method", switch (request.instrument()) {
            case UPI_AUTOPAY -> "upi";
            case CARD -> "card";
            case ENACH -> "emandate";
        });
        registration.put("max_amount", request.maxAmount().amount());
        registration.put("expire_at", request.endAt().getEpochSecond());
        if (request.instrument() != MandateInstrument.ENACH) {
            registration.put("frequency", request.frequency().name().toLowerCase(Locale.ROOT));
        }
        Map<String, Object> customer = new LinkedHashMap<>();
        putIfPresent(customer, "name", request.customerName());
        customer.put("email", request.customerEmail());
        customer.put("contact", request.customerPhone());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer", customer);
        body.put("type", "link");
        body.put("amount", request.registrationAmount() == null ? 0 : request.registrationAmount().amount());
        body.put("currency", request.maxAmount().currency());
        body.put("description", request.description() == null ? "Mandate " + request.mandateId() : request.description());
        body.put("subscription_registration", registration);
        body.put("receipt", request.mandateId());
        body.put("expire_by", request.authorizationExpiresAt().getEpochSecond());
        body.put("sms_notify", 0);
        body.put("email_notify", 0);
        body.put("notes", Map.of(MANDATE_NOTE, request.mandateId()));
        try {
            return registrationResult(account, api.post(account, "/subscription_registration/auth_links", body));
        } catch (RazorpayApi.BadRequest e) {
            if (e.isDuplicate()) {
                Optional<JsonNode> existing = findInvoiceByReceipt(account, request.mandateId());
                if (existing.isPresent()) {
                    return registrationResult(account, existing.get());
                }
            }
            return ProviderMandateResult.failed(null, failure(e), "auth_link_rejected");
        }
    }

    @Override
    public ProviderMandateResult fetchMandate(MerchantAccount account, MandateQuery query) {
        try {
            if (query.providerMandateReference() != null && query.providerCustomerReference() != null) {
                return tokenResult(api.get(account, tokenPath(query.providerCustomerReference(),
                        query.providerMandateReference()), Map.of()), query.providerReference(),
                        query.providerCustomerReference(), null);
            }
            return findInvoice(account, query).map(invoice -> registrationResult(account, invoice))
                    .orElseGet(ProviderMandateResult::notFound);
        } catch (RazorpayApi.BadRequest e) {
            if (e.isNotFound()) {
                return ProviderMandateResult.notFound();
            }
            throw e;
        }
    }

    /** Cancels the registration link while it is unpaid, otherwise the token. */
    @Override
    public ProviderMandateResult revokeMandate(MerchantAccount account, MandateQuery query) {
        try {
            String token = query.providerMandateReference();
            String customer = query.providerCustomerReference();
            if (token == null || customer == null) {
                Optional<JsonNode> invoice = findInvoice(account, query);
                if (invoice.isEmpty()) {
                    return ProviderMandateResult.notFound();
                }
                if (status(invoice.get()).equals("issued")) {
                    return registrationResult(account, api.post(account,
                            "/invoices/" + RazorpayApi.segment(text(invoice.get(), "id")) + "/cancel", Map.of()));
                }
                ProviderMandateResult current = registrationResult(account, invoice.get());
                if (current.providerMandateReference() == null || current.providerCustomerReference() == null) {
                    return current;
                }
                token = current.providerMandateReference();
                customer = current.providerCustomerReference();
            }
            String path = tokenPath(customer, token);
            try {
                if (query.instrument() == MandateInstrument.UPI_AUTOPAY) {
                    api.put(account, path + "/cancel", Map.of());
                } else {
                    api.delete(account, path);
                }
            } catch (RazorpayApi.BadRequest e) {
                ProviderMandateResult current = tokenResult(api.get(account, path, Map.of()), query.providerReference(),
                        customer, null);
                if (current.status() != ProviderMandateResult.Status.REVOKED) {
                    throw e;
                }
                return current;
            }
            return new ProviderMandateResult(ProviderMandateResult.Status.REVOKED, query.providerReference(), token,
                    customer, null, null, null, "cancelled");
        } catch (RazorpayApi.BadRequest e) {
            if (e.isNotFound()) {
                return ProviderMandateResult.notFound();
            }
            throw e;
        }
    }

    /** One order per cycle, keyed by the notification id; the debit later runs on this order. */
    @Override
    public ProviderNotificationResult notifyDebit(MerchantAccount account, DebitNotificationRequest request) {
        if (request.providerMandateReference() == null) {
            return ProviderNotificationResult.failed(null, new ProviderFailure("mandate_token_unknown",
                    "Razorpay has not reported the mandate's token", FailureCategory.PROVIDER), "no_token");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amount().amount());
        body.put("currency", request.amount().currency());
        body.put("receipt", request.notificationId());
        body.put("notes", Map.of(DEBIT_NOTE, request.debitId(), MANDATE_NOTE, request.mandateId()));
        body.put("notification", Map.of("token_id", request.providerMandateReference(),
                "payment_after", request.debitAfter().getEpochSecond()));
        try {
            return notificationResult(createOrFindOrder(account, body, request.notificationId()));
        } catch (RazorpayApi.BadRequest e) {
            return ProviderNotificationResult.failed(null, failure(e), "order_rejected");
        }
    }

    @Override
    public ProviderNotificationResult fetchDebitNotification(MerchantAccount account, DebitNotificationQuery query) {
        try {
            Optional<JsonNode> order = query.providerReference() != null
                    ? Optional.of(api.get(account, "/orders/" + RazorpayApi.segment(query.providerReference()), Map.of()))
                    : findOrderByReceipt(account, query.notificationId());
            return order.map(this::notificationResult).orElseGet(ProviderNotificationResult::notFound);
        } catch (RazorpayApi.BadRequest e) {
            if (e.isNotFound()) {
                return ProviderNotificationResult.notFound();
            }
            throw e;
        }
    }

    /** UPI and card debits run on the cycle's notified order; eNACH debits on an order keyed by the attempt. */
    @Override
    public ProviderPaymentResult executeDebit(MerchantAccount account, ExecuteDebitRequest request) {
        String orderId = request.notificationReference();
        try {
            if (orderId == null) {
                Map<String, Object> order = new LinkedHashMap<>();
                order.put("amount", request.amount().amount());
                order.put("currency", request.amount().currency());
                order.put("receipt", request.attemptId());
                order.put("notes", Map.of(ATTEMPT_NOTE, request.attemptId(), DEBIT_NOTE, request.debitId()));
                orderId = text(createOrFindOrder(account, order, request.attemptId()), "id");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("email", request.customerEmail());
            body.put("contact", request.customerPhone());
            body.put("amount", request.amount().amount());
            body.put("currency", request.amount().currency());
            body.put("order_id", orderId);
            putIfPresent(body, "customer_id", request.providerCustomerReference());
            putIfPresent(body, "token", request.providerMandateReference());
            body.put("recurring", "1");
            putIfPresent(body, "description", request.description());
            body.put("notes", Map.of(ATTEMPT_NOTE, request.attemptId(), DEBIT_NOTE, request.debitId()));
            api.post(account, "/payments/create/recurring", body);
            return ProviderPaymentResult.pending(orderId, "created");
        } catch (RazorpayApi.BadRequest e) {
            return ProviderPaymentResult.failed(orderId, failure(e), "recurring_payment_rejected");
        }
    }

    private ProviderMandateResult registrationResult(MerchantAccount account, JsonNode invoice) {
        String invoiceId = text(invoice, "id");
        String customerId = text(invoice, "customer_id");
        String orderId = text(invoice, "order_id");
        String status = status(invoice);
        return switch (status) {
            case "paid" -> {
                String paymentId = text(invoice, "payment_id");
                String tokenId = paymentId == null ? null
                        : text(api.get(account, "/payments/" + RazorpayApi.segment(paymentId), Map.of()), "token_id");
                yield tokenId == null || customerId == null
                        ? new ProviderMandateResult(ProviderMandateResult.Status.PENDING, invoiceId, null, customerId, orderId,
                                null, null, status)
                        : tokenResult(api.get(account, tokenPath(customerId, tokenId), Map.of()), invoiceId, customerId, orderId);
            }
            case "expired" -> new ProviderMandateResult(ProviderMandateResult.Status.FAILED, invoiceId, null, customerId,
                    orderId, null, registrationExpired(), status);
            case "cancelled" -> new ProviderMandateResult(ProviderMandateResult.Status.REVOKED, invoiceId, null, customerId,
                    orderId, null, null, status);
            default -> {
                String link = text(invoice, "short_url");
                yield new ProviderMandateResult(ProviderMandateResult.Status.PENDING, invoiceId, null, customerId, orderId,
                        link == null ? null : NextAction.redirect(link), null, status);
            }
        };
    }

    /** {@code recurring_details.status}: initiated (the bank has yet to confirm), confirmed, paused, cancelled, rejected. */
    private static ProviderMandateResult tokenResult(JsonNode token, String invoiceId, String customerId, String orderId) {
        JsonNode recurring = token.path("recurring_details");
        String recurringStatus = Optional.ofNullable(text(recurring, "status")).orElse("");
        ProviderMandateResult.Status status = switch (recurringStatus) {
            case "confirmed" -> ProviderMandateResult.Status.ACTIVE;
            case "paused" -> ProviderMandateResult.Status.PAUSED;
            case "cancelled" -> ProviderMandateResult.Status.REVOKED;
            case "expired" -> ProviderMandateResult.Status.EXPIRED;
            case "rejected" -> ProviderMandateResult.Status.FAILED;
            default -> ProviderMandateResult.Status.PENDING;
        };
        return new ProviderMandateResult(status, invoiceId, text(token, "id"), customerId, orderId, null,
                rejection(status, recurring), recurringStatus);
    }

    private static ProviderFailure rejection(ProviderMandateResult.Status status, JsonNode recurring) {
        return status != ProviderMandateResult.Status.FAILED ? null : new ProviderFailure("mandate_rejected",
                Optional.ofNullable(text(recurring, "failure_reason")).orElse("The customer's bank rejected the mandate"),
                FailureCategory.CUSTOMER);
    }

    private static ProviderFailure registrationExpired() {
        return new ProviderFailure("registration_expired",
                "The customer did not authorize the mandate before the registration link expired", FailureCategory.CUSTOMER);
    }

    private ProviderNotificationResult notificationResult(JsonNode order) {
        String orderId = text(order, "id");
        JsonNode notification = order.path("notification");
        return switch (status(notification)) {
            case "delivered" -> ProviderNotificationResult.delivered(orderId,
                    Optional.ofNullable(epochSeconds(notification, "delivered_at")).orElse(clock.instant()), "delivered");
            case "failed" -> ProviderNotificationResult.failed(orderId, notificationFailure(notification), "failed");
            default -> ProviderNotificationResult.pending(orderId, status(notification));
        };
    }

    private static ProviderFailure notificationFailure(JsonNode notification) {
        return new ProviderFailure("notification_failed", Optional.ofNullable(text(notification, "failure_reason"))
                .orElse("Razorpay could not deliver the pre-debit notification"), FailureCategory.CUSTOMER);
    }

    private Optional<JsonNode> findInvoice(MerchantAccount account, MandateQuery query) {
        if (query.providerReference() != null) {
            return Optional.of(api.get(account, "/invoices/" + RazorpayApi.segment(query.providerReference()), Map.of()));
        }
        return findInvoiceByReceipt(account, query.mandateId());
    }

    private Optional<JsonNode> findInvoiceByReceipt(MerchantAccount account, String receipt) {
        for (JsonNode invoice : api.get(account, "/invoices", Map.of("receipt", receipt)).path("items")) {
            if (receipt.equals(text(invoice, "receipt"))) {
                return Optional.of(invoice);
            }
        }
        return Optional.empty();
    }

    private static String tokenPath(String customerId, String tokenId) {
        return "/customers/" + RazorpayApi.segment(customerId) + "/tokens/" + RazorpayApi.segment(tokenId);
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
