package com.payments.gateway.provider.mock;

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
import com.payments.gateway.provider.spi.ProviderCredentialsException;
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
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.provider.spi.SettlementReport;
import com.payments.gateway.provider.spi.WebhookVerificationException;
import com.payments.gateway.provider.mock.MockPsp.MandateState;
import com.payments.gateway.provider.mock.MockPsp.MandateTxn;
import com.payments.gateway.provider.mock.MockPsp.Notification;
import com.payments.gateway.provider.mock.MockPsp.RefundState;
import com.payments.gateway.provider.mock.MockPsp.RefundTxn;
import com.payments.gateway.provider.mock.MockPsp.Txn;
import com.payments.gateway.provider.mock.MockPsp.TxnState;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.PaymentMethod;
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
 * Refund amounts: 07 pending, 08 timeout-but-processed, 09 failed. Mandates (LLD §18.8): a {@code max_amount} ending in
 * 01 or 05 times out as for payments; a debit amount ending in 06 is never notified. Credentials are optional: an
 * {@code api_key} starting with {@code bad_} is rejected, and a {@code webhook_secret} replaces the platform secret for
 * that account.
 */
public class MockPaymentProvider implements PaymentProvider {

    public static final String SIGNATURE_HEADER = "X-Mock-Signature";
    public static final String API_KEY = "api_key";
    public static final String WEBHOOK_SECRET = "webhook_secret";
    private static final long SIGNATURE_TOLERANCE_SECONDS = 300;
    private static final long FEE_BASIS_POINTS = 200;
    private static final List<CredentialField> CREDENTIALS = List.of(
            new CredentialField(API_KEY, true, false), new CredentialField(WEBHOOK_SECRET, true, false));
    private static final CardDetails MOCK_CARD = new CardDetails("visa", "1111");

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

    @Override
    public List<CredentialField> credentialFields() {
        return CREDENTIALS;
    }

    public MockPsp psp() {
        return psp;
    }

    @Override
    public ProviderPaymentResult initiatePayment(MerchantAccount account, InitiatePaymentRequest request) {
        simulateNetwork(account);
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
                create(account, reference, request, completed);
                throw new ProviderTimeoutException(code, "simulated read timeout (request was processed)");
            }
            case 3 -> {
                Txn txn = create(account, reference, request, TxnState.FAILED);
                txn.moveTo(TxnState.FAILED, "transaction_declined", clock.instant());
                return toResult(txn);
            }
            case 4 -> {
                create(account, reference, request, completed);
                return ProviderPaymentResult.pending(reference, "pending");
            }
            case 5 -> throw new ProviderTimeoutException(code, "simulated read timeout (request was not processed)");
            default -> {
                Txn txn = create(account, reference, request, TxnState.REQUIRES_ACTION);
                return ProviderPaymentResult.requiresAction(reference, nextAction(txn), "requires_action");
            }
        }
    }

    @Override
    public ProviderPaymentResult fetchPaymentStatus(MerchantAccount account, PaymentStatusQuery query) {
        simulateNetwork(account);
        return psp.find(query.providerReference(), query.attemptId())
                .map(this::toResult)
                .orElseGet(ProviderPaymentResult::notFound);
    }

    @Override
    public ProviderPaymentResult capture(MerchantAccount account, CaptureRequest request) {
        simulateNetwork(account);
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
    public ProviderPaymentResult voidAuthorization(MerchantAccount account, VoidRequest request) {
        simulateNetwork(account);
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
    public ProviderRefundResult refund(MerchantAccount account, RefundRequest request) {
        simulateNetwork(account);
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
    public ProviderRefundResult fetchRefundStatus(MerchantAccount account, RefundStatusQuery query) {
        simulateNetwork(account);
        return psp.findRefund(query.providerRefundReference(), query.refundId())
                .map(this::toResult)
                .orElseGet(ProviderRefundResult::notFound);
    }

    @Override
    public ProviderMandateResult createMandate(MerchantAccount account, CreateMandateRequest request) {
        simulateNetwork(account);
        var existing = psp.findMandate(null, request.mandateId());
        if (existing.isPresent()) {
            return toResult(existing.get());
        }
        int scenario = (int) (request.maxAmount().amount() % 100);
        if (scenario == 5) {
            throw new ProviderTimeoutException(code, "simulated read timeout (registration was not processed)");
        }
        String secret = account.credential(WEBHOOK_SECRET).orElse(null);
        Txn registration = request.registrationAttemptId() == null ? null
                : psp.create(Ids.newId(prefix()), request.merchantId(), account.id(), secret,
                        request.registrationAttemptId(), request.registrationAmount(),
                        PaymentMethod.mandate(request.mandateId()), false, request.returnUrl(), TxnState.REQUIRES_ACTION,
                        clock.instant());
        MandateTxn mandate = psp.saveMandate(new MandateTxn(Ids.newId(prefix() + "_mdt"), request.merchantId(), account.id(),
                secret, request.mandateId(), request.instrument(), request.maxAmount(), Ids.newId(prefix() + "_cust"),
                registration, request.returnUrl()));
        if (scenario == 1) {
            throw new ProviderTimeoutException(code, "simulated read timeout (registration was processed)");
        }
        return toResult(mandate);
    }

    @Override
    public ProviderMandateResult fetchMandate(MerchantAccount account, MandateQuery query) {
        simulateNetwork(account);
        return psp.findMandate(query.providerReference(), query.mandateId())
                .map(this::toResult)
                .orElseGet(ProviderMandateResult::notFound);
    }

    @Override
    public ProviderMandateResult revokeMandate(MerchantAccount account, MandateQuery query) {
        simulateNetwork(account);
        MandateTxn mandate = psp.findMandate(query.providerReference(), query.mandateId()).orElse(null);
        if (mandate == null) {
            return ProviderMandateResult.notFound();
        }
        if (mandate.moveTo(MandateState.REVOKED) && mandate.registration() != null) {
            mandate.registration().complete(false, clock.instant());
        }
        return toResult(mandate);
    }

    @Override
    public ProviderNotificationResult notifyDebit(MerchantAccount account, DebitNotificationRequest request) {
        simulateNetwork(account);
        var existing = psp.findNotification(null, request.notificationId());
        if (existing.isPresent()) {
            return toResult(existing.get());
        }
        MandateTxn mandate = psp.findMandate(null, request.mandateId()).orElse(null);
        if (mandate == null || mandate.state() != MandateState.ACTIVE) {
            return ProviderNotificationResult.failed(null, new ProviderFailure("mandate_not_active",
                    "The mandate is not active at the PSP", FailureCategory.CUSTOMER), "failed");
        }
        Notification notification = psp.saveNotification(new Notification(Ids.newId(prefix() + "_ntf"),
                request.notificationId(), mandate.mandateReference(), mandate.accountId(), mandate.webhookSecret(),
                request.amount(), request.amount().amount() % 100 != 6, clock.instant()));
        return ProviderNotificationResult.pending(notification.reference(), "pending");
    }

    @Override
    public ProviderNotificationResult fetchDebitNotification(MerchantAccount account, DebitNotificationQuery query) {
        simulateNetwork(account);
        return psp.findNotification(query.providerReference(), query.notificationId())
                .map(MockPaymentProvider::toResult)
                .orElseGet(ProviderNotificationResult::notFound);
    }

    /** Debits on the cycle's notification reference (eNACH: a new one); amount suffixes behave as for payments. */
    @Override
    public ProviderPaymentResult executeDebit(MerchantAccount account, ExecuteDebitRequest request) {
        simulateNetwork(account);
        var existing = psp.find(null, request.attemptId());
        if (existing.isPresent()) {
            return toResult(existing.get());
        }
        MandateTxn mandate = psp.findMandate(null, request.mandateId()).orElse(null);
        if (mandate == null || mandate.state() != MandateState.ACTIVE) {
            return ProviderPaymentResult.failed(null, new ProviderFailure("mandate_not_active",
                    "The mandate is not active at the PSP", FailureCategory.CUSTOMER), "failed");
        }
        String reference = request.notificationReference() != null ? request.notificationReference() : Ids.newId(prefix());
        int scenario = (int) (request.amount().amount() % 100);
        switch (scenario) {
            case 1 -> {
                createDebit(mandate, reference, request, TxnState.CAPTURED);
                throw new ProviderTimeoutException(code, "simulated read timeout (debit was processed)");
            }
            case 3 -> {
                Txn txn = createDebit(mandate, reference, request, TxnState.FAILED);
                txn.moveTo(TxnState.FAILED, "transaction_declined", clock.instant());
                return toResult(txn);
            }
            case 4 -> {
                createDebit(mandate, reference, request, TxnState.CAPTURED);
                return ProviderPaymentResult.pending(reference, "pending");
            }
            case 5 -> throw new ProviderTimeoutException(code, "simulated read timeout (debit was not processed)");
            default -> {
                return toResult(createDebit(mandate, reference, request, TxnState.PENDING));
            }
        }
    }

    @Override
    public List<ProviderEvent> parseWebhook(MerchantAccount account, InboundWebhook webhook) {
        verifySignature(webhookSecret(account), webhook);
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
            return List.of(ProviderEvent.refund(payload.eventId(), payload.type(), payload.providerReference(),
                    payload.merchantReference(), result));
        }
        if (MockWebhookPayload.DISPUTE_UPDATED.equals(payload.type())) {
            return List.of(parseDispute(payload, amount));
        }
        if (MockWebhookPayload.MANDATE_UPDATED.equals(payload.type())) {
            return List.of(parseMandate(payload));
        }
        if (MockWebhookPayload.NOTIFICATION_UPDATED.equals(payload.type())) {
            return List.of(parseNotification(payload));
        }
        ProviderPaymentResult result = switch (payload.status()) {
            case "captured" -> ProviderPaymentResult.succeeded(payload.providerReference(), amount, "captured");
            case "authorized" -> ProviderPaymentResult.authorized(payload.providerReference(), amount, "authorized");
            case "failed" -> ProviderPaymentResult.failed(payload.providerReference(),
                    new ProviderFailure(payload.failureCode(), payload.failureMessage(), FailureCategory.CUSTOMER), "failed");
            case "voided" -> ProviderPaymentResult.voided(payload.providerReference(), "voided");
            default -> ProviderPaymentResult.pending(payload.providerReference(), payload.status());
        };
        if (payload.cardNetwork() != null && payload.cardLast4() != null) {
            result = result.withCard(new CardDetails(payload.cardNetwork(), payload.cardLast4()));
        }
        return List.of(ProviderEvent.payment(payload.eventId(), payload.type(), payload.providerReference(),
                payload.merchantReference(), result));
    }

    private static ProviderEvent parseDispute(MockWebhookPayload payload, Money amount) {
        ProviderDisputeResult.Status status = switch (payload.status()) {
            case "open" -> ProviderDisputeResult.Status.OPEN;
            case "under_review" -> ProviderDisputeResult.Status.UNDER_REVIEW;
            case "won" -> ProviderDisputeResult.Status.WON;
            case "lost" -> ProviderDisputeResult.Status.LOST;
            default -> throw new WebhookVerificationException("malformed payload");
        };
        if (amount == null || payload.providerReference() == null) {
            throw new WebhookVerificationException("malformed payload");
        }
        return ProviderEvent.dispute(payload.eventId(), payload.type(), payload.merchantReference(),
                new ProviderDisputeResult(payload.providerReference(), payload.paymentReference(), status, amount,
                        payload.disputeReason(), payload.respondBy(), payload.status()));
    }

    private static ProviderEvent parseMandate(MockWebhookPayload payload) {
        ProviderMandateResult.Status status = switch (payload.status()) {
            case "confirming" -> ProviderMandateResult.Status.PENDING;
            case "active" -> ProviderMandateResult.Status.ACTIVE;
            case "paused" -> ProviderMandateResult.Status.PAUSED;
            case "revoked" -> ProviderMandateResult.Status.REVOKED;
            case "rejected" -> ProviderMandateResult.Status.FAILED;
            default -> throw new WebhookVerificationException("malformed payload");
        };
        if (payload.providerReference() == null) {
            throw new WebhookVerificationException("malformed payload");
        }
        ProviderFailure failure = status != ProviderMandateResult.Status.FAILED ? null
                : new ProviderFailure(payload.failureCode() == null ? "mandate_rejected" : payload.failureCode(),
                        payload.failureMessage(), FailureCategory.CUSTOMER);
        return ProviderEvent.mandate(payload.eventId(), payload.type(), payload.merchantReference(),
                new ProviderMandateResult(status, payload.providerReference(), payload.mandateReference(),
                        payload.customerReference(), payload.paymentReference(), null, failure, payload.status()));
    }

    private static ProviderEvent parseNotification(MockWebhookPayload payload) {
        String reference = payload.providerReference();
        ProviderNotificationResult result = switch (payload.status()) {
            case "pending" -> ProviderNotificationResult.pending(reference, "pending");
            case "delivered" -> payload.deliveredAt() == null ? null
                    : ProviderNotificationResult.delivered(reference, payload.deliveredAt(), "delivered");
            case "failed" -> ProviderNotificationResult.failed(reference, new ProviderFailure(
                    payload.failureCode() == null ? "notification_failed" : payload.failureCode(),
                    payload.failureMessage(), FailureCategory.CUSTOMER), "failed");
            default -> null;
        };
        if (result == null || reference == null) {
            throw new WebhookVerificationException("malformed payload");
        }
        return ProviderEvent.notification(payload.eventId(), payload.type(), payload.merchantReference(), result);
    }

    /** Builds the webhook body the simulated PSP would send for a mandate's current state. */
    public MockWebhookPayload webhookFor(MandateTxn mandate) {
        boolean rejected = mandate.state() == MandateState.REJECTED;
        return new MockWebhookPayload(Ids.newId("mock_evt"), MockWebhookPayload.MANDATE_UPDATED, mandate.reference(),
                mandate.merchantReference(), mandate.state().name().toLowerCase(Locale.ROOT), mandate.maxAmount().amount(),
                mandate.maxAmount().currency(), rejected ? "mandate_rejected" : null,
                rejected ? "Simulated: the customer rejected the mandate" : null, null, null,
                mandate.registration() == null ? null : mandate.registration().reference(), null, null,
                mandate.mandateReference(), mandate.customerReference(), null);
    }

    /** Builds the webhook body the simulated PSP would send once a notification is delivered or has failed. */
    public MockWebhookPayload webhookFor(Notification notification) {
        return new MockWebhookPayload(Ids.newId("mock_evt"), MockWebhookPayload.NOTIFICATION_UPDATED,
                notification.reference(), notification.notificationId(), notification.delivered() ? "delivered" : "failed",
                notification.amount().amount(), notification.amount().currency(),
                notification.delivered() ? null : "notification_failed",
                notification.delivered() ? null : "Simulated: the notification could not be delivered", null, null, null,
                null, null, notification.mandateReference(), null,
                notification.delivered() ? notification.requestedAt() : null);
    }

    /** Builds the webhook body the simulated PSP would send for a dispute's current state. */
    public MockWebhookPayload webhookFor(MockPsp.DisputeTxn dispute) {
        Txn txn = dispute.payment();
        return new MockWebhookPayload(Ids.newId("mock_evt"), MockWebhookPayload.DISPUTE_UPDATED, dispute.reference(),
                txn.merchantReference(), dispute.state().name().toLowerCase(Locale.ROOT), dispute.amount().amount(),
                dispute.amount().currency(), null, null, null, null, txn.reference(), dispute.reason(), dispute.respondBy(),
                null, null, null);
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
        CardDetails card = cardOf(txn);
        return new MockWebhookPayload(Ids.newId("mock_evt"), MockWebhookPayload.PAYMENT_UPDATED, txn.reference(),
                txn.merchantReference(), status, txn.amount().amount(), txn.amount().currency(), txn.failureCode(),
                txn.failureCode() == null ? null : "Simulated failure: " + txn.failureCode(),
                card == null ? null : card.network(), card == null ? null : card.last4(), null, null, null, null, null,
                null);
    }

    /** The simulated hosted page always "collects" the Visa test card; only its network and last 4 are reported. */
    private static CardDetails cardOf(Txn txn) {
        boolean paid = txn.state() == TxnState.AUTHORIZED || txn.state() == TxnState.CAPTURED || txn.state() == TxnState.VOIDED;
        return txn.method() != null && txn.method().type() == MethodType.CARD && paid ? MOCK_CARD : null;
    }

    /** Signs as the simulated PSP would for the transaction's account (platform secret if the account has none). */
    public String sign(Txn txn, long timestampSeconds, String body) {
        return signForAccount(txn.webhookSecret(), timestampSeconds, body);
    }

    /** {@code accountSecret} is the merchant account's own webhook secret, or null for the platform secret. */
    public String signForAccount(String accountSecret, long timestampSeconds, String body) {
        return sign(accountSecret == null ? properties.webhookSecret() : accountSecret, timestampSeconds, body);
    }

    public String sign(long timestampSeconds, String body) {
        return sign(properties.webhookSecret(), timestampSeconds, body);
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + Hashing.hmacSha256Hex(secret, timestampSeconds + "." + body);
    }

    private String webhookSecret(MerchantAccount account) {
        return account == null ? properties.webhookSecret()
                : account.credential(WEBHOOK_SECRET).orElse(properties.webhookSecret());
    }

    private void verifySignature(String secret, InboundWebhook webhook) {
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
        String expected = Hashing.hmacSha256Hex(secret, timestamp + "." + webhook.body());
        if (!Hashing.constantTimeEquals(expected, signature)) {
            throw new WebhookVerificationException("signature mismatch");
        }
    }

    /** One settlement per merchant and window; the PSP keeps a 2% fee on each capture. */
    @Override
    public SettlementReport fetchSettlementReport(MerchantAccount account, SettlementReportQuery query) {
        simulateNetwork(account);
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
        for (MockPsp.DisputeTxn dispute : psp.disputesOpenedBetween(query.merchantId(), query.from(), query.to())) {
            currency = dispute.amount().currency();
            lines.add(new SettlementReport.Line("line_" + dispute.reference(), SettlementReport.LineType.CHARGEBACK,
                    dispute.reference(), dispute.payment().merchantReference(), dispute.amount(), Money.of(0, currency),
                    settlementId, dispute.createdAt()));
            net -= dispute.amount().amount();
        }
        for (MockPsp.DisputeTxn dispute : psp.disputesWonBetween(query.merchantId(), query.from(), query.to())) {
            currency = dispute.amount().currency();
            lines.add(new SettlementReport.Line("line_" + dispute.reference() + "_reversal",
                    SettlementReport.LineType.CHARGEBACK_REVERSAL, dispute.reference(),
                    dispute.payment().merchantReference(), dispute.amount(), Money.of(0, currency), settlementId,
                    dispute.wonAt()));
            net += dispute.amount().amount();
        }
        if (lines.isEmpty()) {
            return new SettlementReport(List.of(), List.of());
        }
        net -= psp.settlementShortfall(query.merchantId());
        return new SettlementReport(lines, List.of(new SettlementReport.Settlement(settlementId, net, currency,
                "UTR" + query.from().getEpochSecond(), query.to())));
    }

    private Txn create(MerchantAccount account, String reference, InitiatePaymentRequest request, TxnState state) {
        return psp.create(reference, request.merchantId(), account.id(), account.credential(WEBHOOK_SECRET).orElse(null),
                request.attemptId(), request.amount(), request.method(), request.captureMethod() == CaptureMethod.MANUAL,
                request.returnUrl(), state, clock.instant());
    }

    private Txn createDebit(MandateTxn mandate, String reference, ExecuteDebitRequest request, TxnState state) {
        return psp.create(reference, mandate.merchantId(), mandate.accountId(), mandate.webhookSecret(), request.attemptId(),
                request.amount(), PaymentMethod.mandate(request.mandateId()), false, null, state, clock.instant());
    }

    private String prefix() {
        return code.toLowerCase(Locale.ROOT);
    }

    private NextAction nextAction(Txn txn) {
        return switch (txn.method().type()) {
            case CARD, NETBANKING, MANDATE -> NextAction.redirect(properties.publicBaseUrl() + "/simulator/" + code
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
        ProviderPaymentResult result = switch (txn.state()) {
            case REQUIRES_ACTION -> ProviderPaymentResult.requiresAction(txn.reference(), null, "requires_action");
            case PENDING -> ProviderPaymentResult.pending(txn.reference(), "pending");
            case AUTHORIZED -> ProviderPaymentResult.authorized(txn.reference(), txn.amount(), "authorized");
            case CAPTURED -> ProviderPaymentResult.succeeded(txn.reference(), txn.amount(), "captured");
            case VOIDED -> ProviderPaymentResult.voided(txn.reference(), "voided");
            case FAILED -> ProviderPaymentResult.failed(txn.reference(), new ProviderFailure(txn.failureCode(),
                    "Simulated failure: " + txn.failureCode(), failureCategory(txn.failureCode())), "failed");
        };
        CardDetails card = cardOf(txn);
        return card == null ? result : result.withCard(card);
    }

    private static FailureCategory failureCategory(String failureCode) {
        return "transaction_declined".equals(failureCode) ? FailureCategory.ISSUER : FailureCategory.CUSTOMER;
    }

    private ProviderMandateResult toResult(MandateTxn mandate) {
        String registration = mandate.registration() == null ? null : mandate.registration().reference();
        return switch (mandate.state()) {
            case PENDING -> new ProviderMandateResult(ProviderMandateResult.Status.PENDING, mandate.reference(), null,
                    mandate.customerReference(), registration, NextAction.redirect(properties.publicBaseUrl()
                            + "/simulator/" + code + "/mandates/" + mandate.reference()), null, "pending");
            case CONFIRMING -> new ProviderMandateResult(ProviderMandateResult.Status.PENDING, mandate.reference(), null,
                    mandate.customerReference(), registration, null, null, "confirming");
            case ACTIVE -> mandateResult(ProviderMandateResult.Status.ACTIVE, mandate, registration);
            case PAUSED -> mandateResult(ProviderMandateResult.Status.PAUSED, mandate, registration);
            case REVOKED -> mandateResult(ProviderMandateResult.Status.REVOKED, mandate, registration);
            case REJECTED -> new ProviderMandateResult(ProviderMandateResult.Status.FAILED, mandate.reference(), null,
                    mandate.customerReference(), registration, null, new ProviderFailure("mandate_rejected",
                            "Simulated: the customer rejected the mandate", FailureCategory.CUSTOMER), "rejected");
        };
    }

    private static ProviderMandateResult mandateResult(ProviderMandateResult.Status status, MandateTxn mandate,
                                                       String registration) {
        return new ProviderMandateResult(status, mandate.reference(), mandate.mandateReference(),
                mandate.customerReference(), registration, null, null, status.name().toLowerCase(Locale.ROOT));
    }

    /** The simulated PSP delivers a notification at the moment it is requested, unless the scenario fails it. */
    private static ProviderNotificationResult toResult(Notification notification) {
        return notification.delivered()
                ? ProviderNotificationResult.delivered(notification.reference(), notification.requestedAt(), "delivered")
                : ProviderNotificationResult.failed(notification.reference(), new ProviderFailure("notification_failed",
                        "Simulated: the notification could not be delivered", FailureCategory.CUSTOMER), "failed");
    }

    private ProviderRefundResult toResult(RefundTxn refund) {
        return switch (refund.state()) {
            case PENDING -> ProviderRefundResult.pending(refund.reference(), refund.amount());
            case SUCCEEDED -> ProviderRefundResult.succeeded(refund.reference(), refund.amount());
            case FAILED -> ProviderRefundResult.failed(refund.reference(),
                    new ProviderFailure("refund_failed", "Simulated refund failure", FailureCategory.PROVIDER));
        };
    }

    private void simulateNetwork(MerchantAccount account) {
        if (!psp.isAvailable()) {
            throw new ProviderUnavailableException(code, "connection refused (simulated outage)");
        }
        if (account.credential(API_KEY).filter(key -> key.startsWith("bad_")).isPresent()) {
            throw new ProviderCredentialsException(code, "401 authentication failed (simulated invalid api_key)");
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
