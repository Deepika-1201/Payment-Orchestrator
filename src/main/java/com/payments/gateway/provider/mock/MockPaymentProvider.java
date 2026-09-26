package com.payments.gateway.provider.mock;

import com.payments.gateway.provider.spi.InboundWebhook;
import com.payments.gateway.provider.spi.InitiatePaymentRequest;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.provider.spi.ProviderCapabilities;
import com.payments.gateway.provider.spi.ProviderEvent;
import com.payments.gateway.provider.spi.ProviderFailure;
import com.payments.gateway.provider.spi.ProviderPaymentResult;
import com.payments.gateway.provider.spi.ProviderRefundResult;
import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.provider.spi.ProviderRequests.VoidRequest;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.provider.spi.WebhookVerificationException;
import com.payments.gateway.provider.mock.MockPsp.RefundState;
import com.payments.gateway.provider.mock.MockPsp.RefundTxn;
import com.payments.gateway.provider.mock.MockPsp.Txn;
import com.payments.gateway.provider.mock.MockPsp.TxnState;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.UpiFlow;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Simulated PSP with deterministic scenarios selected by the amount's last two digits (see LLD §5):
 * 01 timeout-but-processed, 03 declined, 04 pending-then-silent-success, 05 timeout-never-processed.
 * Refund amounts: 07 pending, 08 timeout-but-processed, 09 failed.
 */
public class MockPaymentProvider implements PaymentProvider {

    public static final String SIGNATURE_HEADER = "X-Mock-Signature";
    private static final long SIGNATURE_TOLERANCE_SECONDS = 300;
    private static final long FEE_BASIS_POINTS = 200;

    private final String code;
    private final ProviderCapabilities capabilities;
    private final MockPsp psp;
    private final MockProviderProperties properties;
    private final JsonCodec json;
    private final Clock clock;

    public MockPaymentProvider(String code, ProviderCapabilities capabilities, MockProviderProperties properties,
                               JsonCodec json, Clock clock) {
        this.code = code;
        this.capabilities = capabilities;
        this.psp = new MockPsp(code);
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    public MockPsp psp() {
        return psp;
    }

    @Override
    public ProviderPaymentResult initiatePayment(InitiatePaymentRequest request) {
        simulateNetwork();
        var existing = psp.find(null, request.attemptId());
        if (existing.isPresent()) {
            return toResult(existing.get());
        }
        boolean manual = request.captureMethod() == CaptureMethod.MANUAL;
        TxnState completed = manual ? TxnState.AUTHORIZED : TxnState.CAPTURED;
        String reference = Ids.newId(code.toLowerCase(Locale.ROOT));
        int scenario = (int) (request.amount().amount() % 100);
        switch (scenario) {
            case 1 -> {
                create(reference, request, completed);
                throw new ProviderTimeoutException(code, "simulated read timeout (request was processed)");
            }
            case 3 -> {
                Txn txn = create(reference, request, TxnState.FAILED);
                txn.moveTo(TxnState.FAILED, "transaction_declined", clock.instant());
                return toResult(txn);
            }
            case 4 -> {
                create(reference, request, completed);
                return ProviderPaymentResult.pending(reference, "pending");
            }
            case 5 -> throw new ProviderTimeoutException(code, "simulated read timeout (request was not processed)");
            default -> {
                Txn txn = create(reference, request, TxnState.REQUIRES_ACTION);
                return ProviderPaymentResult.requiresAction(reference, nextAction(txn), "requires_action");
            }
        }
    }

    @Override
    public ProviderPaymentResult fetchPaymentStatus(PaymentStatusQuery query) {
        simulateNetwork();
        return psp.find(query.providerReference(), query.attemptId())
                .map(this::toResult)
                .orElseGet(ProviderPaymentResult::notFound);
    }

    @Override
    public ProviderPaymentResult capture(CaptureRequest request) {
        simulateNetwork();
        Txn txn = psp.find(request.providerReference(), request.attemptId()).orElse(null);
        if (txn == null) {
            return ProviderPaymentResult.failed(request.providerReference(),
                    new ProviderFailure("payment_not_found", "Unknown payment", FailureCategory.VALIDATION), "error");
        }
        if (!txn.amount().equals(request.amount())) {
            return ProviderPaymentResult.failed(txn.reference(),
                    new ProviderFailure("amount_mismatch", "Capture amount differs", FailureCategory.VALIDATION), "error");
        }
        synchronized (txn) {
            if (txn.state() == TxnState.AUTHORIZED) {
                txn.moveTo(TxnState.CAPTURED, null, clock.instant());
            }
        }
        if (txn.state() == TxnState.CAPTURED) {
            return ProviderPaymentResult.succeeded(txn.reference(), txn.amount(), "captured");
        }
        return ProviderPaymentResult.failed(txn.reference(),
                new ProviderFailure("invalid_state", "Payment is " + txn.state(), FailureCategory.VALIDATION), "error");
    }

    @Override
    public ProviderPaymentResult voidAuthorization(VoidRequest request) {
        simulateNetwork();
        Txn txn = psp.find(request.providerReference(), request.attemptId()).orElse(null);
        if (txn == null) {
            return ProviderPaymentResult.failed(request.providerReference(),
                    new ProviderFailure("payment_not_found", "Unknown payment", FailureCategory.VALIDATION), "error");
        }
        synchronized (txn) {
            if (txn.state() == TxnState.AUTHORIZED) {
                txn.moveTo(TxnState.VOIDED, null, clock.instant());
            }
        }
        if (txn.state() == TxnState.VOIDED) {
            return ProviderPaymentResult.voided(txn.reference(), "voided");
        }
        return ProviderPaymentResult.failed(txn.reference(),
                new ProviderFailure("invalid_state", "Payment is " + txn.state(), FailureCategory.VALIDATION), "error");
    }

    @Override
    public ProviderRefundResult refund(RefundRequest request) {
        simulateNetwork();
        var existing = psp.findRefund(null, request.refundId());
        if (existing.isPresent()) {
            return toResult(existing.get());
        }
        Txn txn = psp.find(request.paymentProviderReference(), request.attemptId()).orElse(null);
        int scenario = (int) (request.amount().amount() % 100);
        RefundState state = scenario == 9 ? RefundState.FAILED : RefundState.SUCCEEDED;
        boolean allowed = txn != null && (state == RefundState.FAILED
                ? txn.state() == TxnState.CAPTURED
                : txn.reserveRefund(request.amount().amount()));
        if (!allowed) {
            return ProviderRefundResult.failed(null,
                    new ProviderFailure("refund_not_allowed", "Payment not captured or refund exceeds captured amount",
                            FailureCategory.VALIDATION));
        }
        String reference = Ids.newId(code.toLowerCase(Locale.ROOT) + "_rfnd");
        RefundTxn refund = psp.saveRefund(new RefundTxn(reference, request.refundId(), txn.reference(), request.amount(),
                state, clock.instant()));
        return switch (scenario) {
            case 7 -> ProviderRefundResult.pending(refund.reference(), refund.amount());
            case 8 -> throw new ProviderTimeoutException(code, "simulated refund timeout (request was processed)");
            default -> toResult(refund);
        };
    }

    @Override
    public ProviderRefundResult fetchRefundStatus(RefundStatusQuery query) {
        simulateNetwork();
        return psp.findRefund(query.providerRefundReference(), query.refundId())
                .map(this::toResult)
                .orElseGet(ProviderRefundResult::notFound);
    }

    @Override
    public List<ProviderEvent> parseWebhook(InboundWebhook webhook) {
        verifySignature(webhook);
        MockWebhookPayload payload;
        try {
            payload = json.read(webhook.body(), MockWebhookPayload.class);
        } catch (RuntimeException e) {
            throw new WebhookVerificationException("malformed payload");
        }
        if (payload.eventId() == null || payload.status() == null) {
            throw new WebhookVerificationException("malformed payload");
        }
        Money amount = payload.amount() == null ? null : Money.of(payload.amount(), payload.currency());
        if (MockWebhookPayload.REFUND_UPDATED.equals(payload.type())) {
            ProviderRefundResult result = switch (payload.status()) {
                case "succeeded" -> ProviderRefundResult.succeeded(payload.providerReference(), amount);
                case "failed" -> ProviderRefundResult.failed(payload.providerReference(),
                        new ProviderFailure(payload.failureCode(), payload.failureMessage(), FailureCategory.PROVIDER));
                default -> ProviderRefundResult.pending(payload.providerReference(), amount);
            };
            return List.of(new ProviderEvent(payload.eventId(), ProviderEvent.Kind.REFUND, payload.type(),
                    payload.providerReference(), payload.merchantReference(), null, result));
        }
        ProviderPaymentResult result = switch (payload.status()) {
            case "captured" -> ProviderPaymentResult.succeeded(payload.providerReference(), amount, "captured");
            case "authorized" -> ProviderPaymentResult.authorized(payload.providerReference(), amount, "authorized");
            case "failed" -> ProviderPaymentResult.failed(payload.providerReference(),
                    new ProviderFailure(payload.failureCode(), payload.failureMessage(), FailureCategory.CUSTOMER), "failed");
            case "voided" -> ProviderPaymentResult.voided(payload.providerReference(), "voided");
            default -> ProviderPaymentResult.pending(payload.providerReference(), payload.status());
        };
        return List.of(new ProviderEvent(payload.eventId(), ProviderEvent.Kind.PAYMENT, payload.type(),
                payload.providerReference(), payload.merchantReference(), result, null));
    }

    /** Builds the webhook body the simulated PSP would send for the current transaction state. */
    public MockWebhookPayload webhookFor(Txn txn) {
        String status = switch (txn.state()) {
            case CAPTURED -> "captured";
            case AUTHORIZED -> "authorized";
            case FAILED -> "failed";
            case VOIDED -> "voided";
            default -> "pending";
        };
        return new MockWebhookPayload(Ids.newId("mock_evt"), MockWebhookPayload.PAYMENT_UPDATED, txn.reference(),
                txn.merchantReference(), status, txn.amount().amount(), txn.amount().currency(), txn.failureCode(),
                txn.failureCode() == null ? null : "Simulated failure: " + txn.failureCode());
    }

    public String sign(long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + Hashing.hmacSha256Hex(properties.webhookSecret(), timestampSeconds + "." + body);
    }

    private void verifySignature(InboundWebhook webhook) {
        String header = webhook.header(SIGNATURE_HEADER);
        if (header == null) {
            throw new WebhookVerificationException("missing signature");
        }
        long timestamp = -1;
        String signature = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    throw new WebhookVerificationException("invalid signature timestamp");
                }
            } else if (kv.length == 2 && kv[0].equals("v1")) {
                signature = kv[1];
            }
        }
        if (timestamp < 0 || signature == null) {
            throw new WebhookVerificationException("malformed signature header");
        }
        if (Math.abs(clock.instant().getEpochSecond() - timestamp) > SIGNATURE_TOLERANCE_SECONDS) {
            throw new WebhookVerificationException("signature timestamp outside tolerance");
        }
        String expected = Hashing.hmacSha256Hex(properties.webhookSecret(), timestamp + "." + webhook.body());
        if (!Hashing.constantTimeEquals(expected, signature)) {
            throw new WebhookVerificationException("signature mismatch");
        }
    }

    /** One settlement per merchant and window; the PSP keeps a 2% fee on each capture. */
    @Override
    public SettlementReport fetchSettlementReport(SettlementReportQuery query) {
        simulateNetwork();
        String settlementId = "setl_" + code.toLowerCase(Locale.ROOT) + "_" + query.merchantId() + "_" + query.from().getEpochSecond();
        List<SettlementReport.Line> lines = new ArrayList<>();
        long net = 0;
        String currency = "INR";
        for (Txn txn : psp.capturedBetween(query.merchantId(), query.from(), query.to())) {
            if (psp.isDroppedFromReport(txn.reference())) {
                continue;
            }
            currency = txn.amount().currency();
            Money gross = Money.of(psp.reportedAmount(txn), currency);
            Money fee = Money.of(gross.amount() * FEE_BASIS_POINTS / 10_000, currency);
            int copies = psp.isDuplicatedInReport(txn.reference()) ? 2 : 1;
            for (int copy = 0; copy < copies; copy++) {
                lines.add(new SettlementReport.Line("line_" + txn.reference() + (copy == 0 ? "" : "_" + copy),
                        SettlementReport.LineType.PAYMENT, txn.reference(), txn.merchantReference(), gross, fee,
                        settlementId, txn.capturedAt()));
                net += gross.amount() - fee.amount();
            }
        }
        for (RefundTxn refund : psp.refundsBetween(query.merchantId(), query.from(), query.to())) {
            currency = refund.amount().currency();
            lines.add(new SettlementReport.Line("line_" + refund.reference(), SettlementReport.LineType.REFUND,
                    refund.reference(), refund.merchantReference(), refund.amount(), Money.of(0, currency), settlementId,
                    refund.createdAt()));
            net -= refund.amount().amount();
        }
        if (lines.isEmpty()) {
            return new SettlementReport(List.of(), List.of());
        }
        net -= psp.settlementShortfall(query.merchantId());
        return new SettlementReport(lines, List.of(new SettlementReport.Settlement(settlementId, net, currency,
                "UTR" + query.from().getEpochSecond(), query.to())));
    }

    private Txn create(String reference, InitiatePaymentRequest request, TxnState state) {
        return psp.create(reference, request.merchantId(), request.attemptId(), request.amount(), request.method(),
                request.captureMethod() == CaptureMethod.MANUAL, request.returnUrl(), state, clock.instant());
    }

    private NextAction nextAction(Txn txn) {
        return switch (txn.method().type()) {
            case CARD, NETBANKING -> NextAction.redirect(properties.publicBaseUrl() + "/simulator/" + code
                    + "/checkout/" + txn.reference());
            case UPI -> {
                UpiFlow flow = txn.method().upiFlow();
                if (flow == UpiFlow.COLLECT) {
                    yield NextAction.awaitApproval();
                }
                String uri = "upi://pay?pa=" + code.toLowerCase(Locale.ROOT) + "@mockbank&pn="
                        + URLEncoder.encode("Mock Merchant", StandardCharsets.UTF_8).replace("+", "%20")
                        + "&tr=" + txn.merchantReference() + "&am=" + txn.amount().toDecimalString()
                        + "&cu=" + txn.amount().currency();
                yield flow == UpiFlow.QR
                        ? NextAction.displayQr(uri, clock.instant().plus(Duration.ofMinutes(5)))
                        : NextAction.upiIntent(uri);
            }
        };
    }

    private ProviderPaymentResult toResult(Txn txn) {
        return switch (txn.state()) {
            case REQUIRES_ACTION -> ProviderPaymentResult.requiresAction(txn.reference(), null, "requires_action");
            case PENDING -> ProviderPaymentResult.pending(txn.reference(), "pending");
            case AUTHORIZED -> ProviderPaymentResult.authorized(txn.reference(), txn.amount(), "authorized");
            case CAPTURED -> ProviderPaymentResult.succeeded(txn.reference(), txn.amount(), "captured");
            case VOIDED -> ProviderPaymentResult.voided(txn.reference(), "voided");
            case FAILED -> ProviderPaymentResult.failed(txn.reference(), new ProviderFailure(txn.failureCode(),
                    "Simulated failure: " + txn.failureCode(), failureCategory(txn.failureCode())), "failed");
        };
    }

    private static FailureCategory failureCategory(String failureCode) {
        return "transaction_declined".equals(failureCode) ? FailureCategory.ISSUER : FailureCategory.CUSTOMER;
    }

    private ProviderRefundResult toResult(RefundTxn refund) {
        return switch (refund.state()) {
            case PENDING -> ProviderRefundResult.pending(refund.reference(), refund.amount());
            case SUCCEEDED -> ProviderRefundResult.succeeded(refund.reference(), refund.amount());
            case FAILED -> ProviderRefundResult.failed(refund.reference(),
                    new ProviderFailure("refund_failed", "Simulated refund failure", FailureCategory.PROVIDER));
        };
    }

    private void simulateNetwork() {
        if (!psp.isAvailable()) {
            throw new ProviderUnavailableException(code, "connection refused (simulated outage)");
        }
        Duration latency = properties.latency();
        if (!latency.isZero()) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderTimeoutException(code, "interrupted", e);
            }
        }
    }
}
