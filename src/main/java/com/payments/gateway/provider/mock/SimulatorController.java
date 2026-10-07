package com.payments.gateway.provider.mock;

import com.payments.gateway.provider.mock.MockPsp.MandateState;
import com.payments.gateway.provider.mock.MockPsp.MandateTxn;
import com.payments.gateway.provider.mock.MockPsp.Txn;
import com.payments.gateway.provider.spi.ProviderCapabilities.MethodSupport;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * Plays the PSP's role for local/test use: a hosted checkout page, completion of customer actions (which emits a
 * signed webhook back to the gateway) and outage simulation.
 */
@RestController
@RequestMapping("/simulator")
@ConditionalOnProperty(prefix = "pg.providers.mock", name = "enabled", havingValue = "true")
public class SimulatorController {

    private final Map<String, MockPaymentProvider> providers;
    private final MockWebhookSender webhookSender;
    private final Clock clock;

    public SimulatorController(List<MockPaymentProvider> providers, MockWebhookSender webhookSender, Clock clock) {
        this.providers = providers.stream().collect(java.util.stream.Collectors.toMap(MockPaymentProvider::code, p -> p));
        this.webhookSender = webhookSender;
        this.clock = clock;
    }

    /** {@code emi_tenure_months}: the plan a card EMI customer picks (LLD §20.5); the shortest offered when absent. */
    public record CompleteRequest(String outcome, Boolean duplicateWebhook, Integer emiTenureMonths) {
    }

    public record AvailabilityRequest(boolean available) {
    }

    public record AnomalyRequest(String type, String merchantId, String providerReference, Long amount) {
    }

    /** {@code send_webhook = false} leaves the gateway to learn about the dispute from the settlement report. */
    public record DisputeRequest(Long amount, String reason, Boolean sendWebhook) {
    }

    public record DisputeStatusRequest(String status, Boolean sendWebhook) {
    }

    /** {@code send_webhook = false} leaves the gateway to learn the outcome from its status checks. */
    public record MandateCompleteRequest(String outcome, Boolean sendWebhook) {
    }

    public record MandateStatusRequest(String status, Boolean sendWebhook) {
    }

    /**
     * The customer approves ({@code success}) or rejects ({@code failure}) a registration, or approves one the bank has
     * yet to confirm ({@code confirming}): webhooks for its authorization charge and the mandate.
     */
    @PostMapping("/{provider}/mandates/{reference}/complete")
    public Map<String, Object> completeMandate(@PathVariable String provider, @PathVariable String reference,
                                               @RequestBody MandateCompleteRequest request) {
        MandateTxn mandate = authorizeAndNotify(provider, reference, String.valueOf(request.outcome()),
                !Boolean.FALSE.equals(request.sendWebhook()));
        return mandateView(mandate);
    }

    /** Plays the customer pausing, resuming or revoking in their UPI or bank app, or the bank confirming ({@code active}). */
    @PostMapping("/{provider}/mandates/{reference}/status")
    public Map<String, Object> updateMandate(@PathVariable String provider, @PathVariable String reference,
                                             @RequestBody MandateStatusRequest request) {
        MockPaymentProvider mock = provider(provider);
        MandateTxn mandate = mandate(provider, reference);
        MandateState target = switch (String.valueOf(request.status())) {
            case "paused" -> MandateState.PAUSED;
            case "active" -> MandateState.ACTIVE;
            case "revoked" -> MandateState.REVOKED;
            default -> throw GatewayException.validation("status", "must be one of paused, active, revoked");
        };
        boolean moved = target == MandateState.ACTIVE && mandate.state() == MandateState.CONFIRMING
                ? mandate.confirm(Ids.newId(provider.toLowerCase(Locale.ROOT) + "_tok"))
                : mandate.moveTo(target);
        if (!moved) {
            throw GatewayException.invalidState("Mandate " + reference + " is " + mandate.state());
        }
        if (!Boolean.FALSE.equals(request.sendWebhook())) {
            webhookSender.send(gatewayBaseUrl(), mock, mandate.accountId(), mandate.webhookSecret(),
                    mock.webhookFor(mandate));
        }
        return mandateView(mandate);
    }

    @GetMapping(value = "/{provider}/mandates/{reference}", produces = MediaType.TEXT_HTML_VALUE)
    public String mandatePage(@PathVariable String provider, @PathVariable String reference) {
        MandateTxn mandate = mandate(provider, reference);
        String action = "/simulator/" + HtmlUtils.htmlEscape(provider) + "/mandates/" + HtmlUtils.htmlEscape(reference);
        return page("Mock PSP mandate authorization",
                "<p>" + HtmlUtils.htmlEscape(provider) + " &middot; " + mandate.instrument().name() + " &middot; up to "
                        + mandate.maxAmount().currency() + " " + mandate.maxAmount().toDecimalString() + " per debit</p>"
                        + "<form method=\"post\" action=\"" + action + "\"><input type=\"hidden\" name=\"outcome\" value=\"success\">"
                        + "<button type=\"submit\">Approve</button></form>"
                        + "<form method=\"post\" action=\"" + action + "\"><input type=\"hidden\" name=\"outcome\" value=\"failure\">"
                        + "<button type=\"submit\">Reject</button></form>");
    }

    @PostMapping(value = "/{provider}/mandates/{reference}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    public String submitMandate(@PathVariable String provider, @PathVariable String reference,
                                @RequestParam String outcome) {
        MandateTxn mandate = authorizeAndNotify(provider, reference, outcome, true);
        return page("Mandate " + mandate.state().name().toLowerCase(Locale.ROOT), returnLink(mandate.returnUrl()));
    }

    private MandateTxn authorizeAndNotify(String provider, String reference, String outcome, boolean sendWebhooks) {
        MockPaymentProvider mock = provider(provider);
        MandateTxn mandate = mandate(provider, reference);
        boolean approved = !"failure".equalsIgnoreCase(outcome);
        boolean moved = "confirming".equalsIgnoreCase(outcome) ? mandate.awaitConfirmation()
                : mandate.authorize(approved, Ids.newId(provider.toLowerCase(Locale.ROOT) + "_tok"));
        if (!moved) {
            throw GatewayException.invalidState("Mandate " + reference + " is already " + mandate.state());
        }
        Txn registration = mandate.registration();
        if (registration != null) {
            registration.complete(approved, clock.instant());
        }
        if (sendWebhooks) {
            if (registration != null) {
                webhookSender.send(gatewayBaseUrl(), mock, registration, mock.webhookFor(registration));
            }
            webhookSender.send(gatewayBaseUrl(), mock, mandate.accountId(), mandate.webhookSecret(),
                    mock.webhookFor(mandate));
        }
        return mandate;
    }

    private MandateTxn mandate(String provider, String reference) {
        return provider(provider).psp().findMandate(reference, null)
                .orElseThrow(() -> GatewayException.notFound("Mock mandate", reference));
    }

    private static Map<String, Object> mandateView(MandateTxn mandate) {
        return Map.of("provider_reference", mandate.reference(), "state", mandate.state().name().toLowerCase(Locale.ROOT));
    }

    private static String gatewayBaseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    /** Raises a chargeback on a captured transaction, as the card network or NPCI would through the PSP. */
    @PostMapping("/{provider}/payments/{reference}/dispute")
    public Map<String, Object> openDispute(@PathVariable String provider, @PathVariable String reference,
                                           @RequestBody DisputeRequest request) {
        MockPaymentProvider mock = provider(provider);
        Txn txn = transaction(provider, reference);
        if (txn.state() != MockPsp.TxnState.CAPTURED) {
            throw GatewayException.invalidState("Only captured transactions can be disputed; " + reference + " is " + txn.state());
        }
        Money captured = txn.capturedAmount();
        long amount = request.amount() == null ? captured.amount() : request.amount();
        if (amount <= 0 || amount > captured.amount()) {
            throw GatewayException.validation("amount", "must be between 1 and the captured amount");
        }
        MockPsp.DisputeTxn dispute = mock.psp().openDispute(txn, Money.of(amount, captured.currency()),
                request.reason() == null ? "fraudulent" : request.reason(), clock.instant());
        notifyDispute(mock, dispute, request.sendWebhook());
        return Map.of("dispute_id", dispute.reference(), "status", "open");
    }

    @PostMapping("/{provider}/disputes/{dispute}/status")
    public Map<String, Object> updateDispute(@PathVariable String provider, @PathVariable String dispute,
                                             @RequestBody DisputeStatusRequest request) {
        MockPaymentProvider mock = provider(provider);
        MockPsp.DisputeTxn found = mock.psp().findDispute(dispute)
                .orElseThrow(() -> GatewayException.notFound("Mock dispute", dispute));
        MockPsp.DisputeState state;
        try {
            state = MockPsp.DisputeState.valueOf(String.valueOf(request.status()).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw GatewayException.validation("status", "must be one of open, under_review, won, lost");
        }
        mock.psp().moveDispute(found, state, clock.instant());
        notifyDispute(mock, found, request.sendWebhook());
        return Map.of("dispute_id", found.reference(), "status", state.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void notifyDispute(MockPaymentProvider mock, MockPsp.DisputeTxn dispute, Boolean sendWebhook) {
        if (Boolean.FALSE.equals(sendWebhook)) {
            return;
        }
        String gatewayBaseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        webhookSender.send(gatewayBaseUrl, mock, dispute.payment(), mock.webhookFor(dispute));
    }

    @PostMapping("/{provider}/payments/{reference}/complete")
    public Map<String, Object> complete(@PathVariable String provider, @PathVariable String reference,
                                        @RequestBody CompleteRequest request) {
        boolean success = !"failure".equalsIgnoreCase(request.outcome());
        int deliveries = Boolean.TRUE.equals(request.duplicateWebhook()) ? 2 : 1;
        Txn txn = completeAndNotify(provider, reference, success, request.emiTenureMonths(), deliveries);
        return Map.of("provider_reference", txn.reference(), "state", txn.state().name().toLowerCase(java.util.Locale.ROOT));
    }

    @PostMapping("/{provider}/availability")
    public Map<String, Object> availability(@PathVariable String provider, @RequestBody AvailabilityRequest request) {
        provider(provider).psp().setAvailable(request.available());
        return Map.of("provider", provider, "available", request.available());
    }

    /** Makes the next settlement report disagree with the gateway, to exercise reconciliation. */
    @PostMapping("/{provider}/report-anomalies")
    public Map<String, Object> reportAnomaly(@PathVariable String provider, @RequestBody AnomalyRequest request) {
        MockPsp psp = provider(provider).psp();
        String type = request.type() == null ? "" : request.type();
        switch (type) {
            case "drop" -> psp.dropFromReport(required(request.providerReference(), "provider_reference"));
            case "duplicate" -> psp.duplicateInReport(required(request.providerReference(), "provider_reference"));
            case "amount_override" -> psp.overrideReportedAmount(required(request.providerReference(), "provider_reference"),
                    required(request.amount(), "amount"));
            case "orphan_capture" -> psp.addOrphanCapture(required(request.merchantId(), "merchant_id"),
                    Money.of(required(request.amount(), "amount"), "INR"), clock.instant());
            case "settlement_shortfall" -> psp.shortSettlement(required(request.merchantId(), "merchant_id"),
                    required(request.amount(), "amount"));
            case "clear" -> psp.clearAnomalies();
            default -> throw GatewayException.validation("type",
                    "must be one of drop, duplicate, amount_override, orphan_capture, settlement_shortfall, clear");
        }
        return Map.of("provider", provider, "type", type);
    }

    @GetMapping(value = "/{provider}/checkout/{reference}", produces = MediaType.TEXT_HTML_VALUE)
    public String checkoutPage(@PathVariable String provider, @PathVariable String reference) {
        Txn txn = transaction(provider, reference);
        String action = "/simulator/" + HtmlUtils.htmlEscape(provider) + "/checkout/" + HtmlUtils.htmlEscape(reference);
        String instrument = txn.method().provider() == null ? "" : " &middot; " + HtmlUtils.htmlEscape(txn.method().provider());
        StringBuilder tenures = new StringBuilder();
        if (txn.method().type() == MethodType.EMI) {
            tenures.append("<label>Plan <select name=\"emi_tenure_months\">");
            emiTenures(provider(provider)).forEach(months -> tenures.append("<option value=\"").append(months).append("\">")
                    .append(months).append(" months</option>"));
            tenures.append("</select></label>");
        }
        return page("Mock PSP checkout",
                "<p>" + HtmlUtils.htmlEscape(provider) + " &middot; " + txn.method().type().name() + instrument + " &middot; "
                        + txn.amount().currency() + " " + txn.amount().toDecimalString() + "</p>"
                        + "<form method=\"post\" action=\"" + action + "\"><input type=\"hidden\" name=\"outcome\" value=\"success\">"
                        + tenures + "<button type=\"submit\">Pay</button></form>"
                        + "<form method=\"post\" action=\"" + action + "\"><input type=\"hidden\" name=\"outcome\" value=\"failure\">"
                        + "<button type=\"submit\">Fail</button></form>");
    }

    @PostMapping(value = "/{provider}/checkout/{reference}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    public String submitCheckout(@PathVariable String provider, @PathVariable String reference,
                                 @RequestParam String outcome,
                                 @RequestParam(name = "emi_tenure_months", required = false) Integer emiTenureMonths) {
        Txn txn = completeAndNotify(provider, reference, "success".equalsIgnoreCase(outcome), emiTenureMonths, 1);
        return page("Payment " + txn.state().name().toLowerCase(java.util.Locale.ROOT), returnLink(txn.returnUrl()));
    }

    private static String returnLink(String returnUrl) {
        if (returnUrl != null && (returnUrl.startsWith("https://") || returnUrl.startsWith("http://"))) {
            return "<p><a href=\"" + HtmlUtils.htmlEscape(returnUrl) + "\">Return to merchant</a></p>";
        }
        return "";
    }

    private Txn completeAndNotify(String provider, String reference, boolean success, Integer emiTenureMonths,
                                  int deliveries) {
        MockPaymentProvider mock = provider(provider);
        Txn txn = transaction(provider, reference);
        if (emiTenureMonths != null && (txn.method().type() != MethodType.EMI || !emiTenures(mock).contains(emiTenureMonths))) {
            throw GatewayException.validation("emi_tenure_months", "must be one of " + emiTenures(mock)
                    + " months, for a card EMI payment");
        }
        if (!txn.complete(success, emiTenureMonths, clock.instant())) {
            throw GatewayException.invalidState("Transaction " + reference + " is already " + txn.state());
        }
        String gatewayBaseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        MockWebhookPayload payload = mock.webhookFor(txn);
        for (int i = 0; i < deliveries; i++) {
            webhookSender.send(gatewayBaseUrl, mock, txn, payload);
        }
        return txn;
    }

    private MockPaymentProvider provider(String code) {
        MockPaymentProvider provider = providers.get(code);
        if (provider == null) {
            throw new GatewayException(ErrorCode.RESOURCE_NOT_FOUND, "Unknown mock provider " + code);
        }
        return provider;
    }

    private static List<Integer> emiTenures(MockPaymentProvider mock) {
        MethodSupport emi = mock.capabilities().methods().get(MethodType.EMI);
        return emi == null ? List.of() : emi.tenures().stream().sorted().toList();
    }

    private Txn transaction(String provider, String reference) {
        return provider(provider).psp().find(reference, null)
                .orElseThrow(() -> GatewayException.notFound("Mock transaction", reference));
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw GatewayException.validation(field, "is required for this anomaly type");
        }
        return value;
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + HtmlUtils.htmlEscape(title)
                + "</title></head><body><h1>" + HtmlUtils.htmlEscape(title) + "</h1>" + body + "</body></html>";
    }
}
