package com.payments.gateway.provider.mock;

import com.payments.gateway.provider.mock.MockPsp.Txn;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import java.util.List;
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

    public SimulatorController(List<MockPaymentProvider> providers, MockWebhookSender webhookSender) {
        this.providers = providers.stream().collect(java.util.stream.Collectors.toMap(MockPaymentProvider::code, p -> p));
        this.webhookSender = webhookSender;
    }

    public record CompleteRequest(String outcome, Boolean duplicateWebhook) {
    }

    public record AvailabilityRequest(boolean available) {
    }

    @PostMapping("/{provider}/payments/{reference}/complete")
    public Map<String, Object> complete(@PathVariable String provider, @PathVariable String reference,
                                        @RequestBody CompleteRequest request) {
        boolean success = !"failure".equalsIgnoreCase(request.outcome());
        int deliveries = Boolean.TRUE.equals(request.duplicateWebhook()) ? 2 : 1;
        Txn txn = completeAndNotify(provider, reference, success, deliveries);
        return Map.of("provider_reference", txn.reference(), "state", txn.state().name().toLowerCase(java.util.Locale.ROOT));
    }

    @PostMapping("/{provider}/availability")
    public Map<String, Object> availability(@PathVariable String provider, @RequestBody AvailabilityRequest request) {
        provider(provider).psp().setAvailable(request.available());
        return Map.of("provider", provider, "available", request.available());
    }

    @GetMapping(value = "/{provider}/checkout/{reference}", produces = MediaType.TEXT_HTML_VALUE)
    public String checkoutPage(@PathVariable String provider, @PathVariable String reference) {
        Txn txn = transaction(provider, reference);
        String action = "/simulator/" + HtmlUtils.htmlEscape(provider) + "/checkout/" + HtmlUtils.htmlEscape(reference);
        return page("Mock PSP checkout",
                "<p>" + HtmlUtils.htmlEscape(provider) + " &middot; " + txn.method().type().name() + " &middot; "
                        + txn.amount().currency() + " " + txn.amount().toDecimalString() + "</p>"
                        + "<form method=\"post\" action=\"" + action + "\"><input type=\"hidden\" name=\"outcome\" value=\"success\">"
                        + "<button type=\"submit\">Pay</button></form>"
                        + "<form method=\"post\" action=\"" + action + "\"><input type=\"hidden\" name=\"outcome\" value=\"failure\">"
                        + "<button type=\"submit\">Fail</button></form>");
    }

    @PostMapping(value = "/{provider}/checkout/{reference}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    public String submitCheckout(@PathVariable String provider, @PathVariable String reference,
                                 @RequestParam String outcome) {
        Txn txn = completeAndNotify(provider, reference, "success".equalsIgnoreCase(outcome), 1);
        String returnLink = "";
        String returnUrl = txn.returnUrl();
        if (returnUrl != null && (returnUrl.startsWith("https://") || returnUrl.startsWith("http://"))) {
            returnLink = "<p><a href=\"" + HtmlUtils.htmlEscape(returnUrl) + "\">Return to merchant</a></p>";
        }
        return page("Payment " + txn.state().name().toLowerCase(java.util.Locale.ROOT), returnLink);
    }

    private Txn completeAndNotify(String provider, String reference, boolean success, int deliveries) {
        MockPaymentProvider mock = provider(provider);
        Txn txn = transaction(provider, reference);
        if (!txn.complete(success)) {
            throw GatewayException.invalidState("Transaction " + reference + " is already " + txn.state());
        }
        String gatewayBaseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        MockWebhookPayload payload = mock.webhookFor(txn);
        for (int i = 0; i < deliveries; i++) {
            webhookSender.send(gatewayBaseUrl, mock, payload);
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

    private Txn transaction(String provider, String reference) {
        return provider(provider).psp().find(reference, null)
                .orElseThrow(() -> GatewayException.notFound("Mock transaction", reference));
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + HtmlUtils.htmlEscape(title)
                + "</title></head><body><h1>" + HtmlUtils.htmlEscape(title) + "</h1>" + body + "</body></html>";
    }
}
