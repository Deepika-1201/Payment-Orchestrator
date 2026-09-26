package com.payments.gateway.checkout.web;

import com.payments.gateway.checkout.CheckoutOption;
import com.payments.gateway.checkout.CheckoutProperties;
import com.payments.gateway.checkout.CheckoutService;
import com.payments.gateway.checkout.web.CheckoutPages.Notice;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Customer-facing hosted checkout. The form posts back here and is always answered with a 303 to the page
 * (post/redirect/get), so refreshes never resubmit and the page reflects the payment's current state.
 */
@Controller
@RequestMapping("/checkout")
public class HostedCheckoutController {

    private static final Logger log = LoggerFactory.getLogger(HostedCheckoutController.class);
    private static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");
    private static final Pattern VPA = Pattern.compile("[A-Za-z0-9._\\-]{2,200}@[A-Za-z][A-Za-z0-9]{1,50}");

    private final CheckoutService checkout;
    private final CheckoutProperties properties;

    public HostedCheckoutController(CheckoutService checkout, CheckoutProperties properties) {
        this.checkout = checkout;
        this.properties = properties;
    }

    @GetMapping("/{token}")
    public ResponseEntity<String> page(@PathVariable String token, @RequestParam(required = false) String error) {
        return checkout.open(token)
                .map(page -> html(HttpStatus.OK, CheckoutPages.render(page, properties.banks(), Notice.fromParam(error))))
                .orElseGet(() -> html(HttpStatus.NOT_FOUND, CheckoutPages.notFound()));
    }

    @PostMapping(value = "/{token}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> pay(@PathVariable String token,
                                      @RequestParam(required = false) String method,
                                      @RequestParam(required = false) String vpa,
                                      @RequestParam(required = false) String bank,
                                      HttpServletRequest request) {
        Optional<CheckoutService.Page> found = checkout.open(token);
        if (found.isEmpty()) {
            return html(HttpStatus.NOT_FOUND, CheckoutPages.notFound());
        }
        CheckoutService.Page page = found.get();
        if (!"requires_payment_method".equals(page.payment().status())) {
            return backToPage(token, null);
        }
        Optional<CheckoutOption> option = CheckoutOption.fromFormValue(method).filter(page.options()::contains);
        if (option.isEmpty()) {
            return backToPage(token, Notice.METHOD_UNAVAILABLE);
        }
        PaymentMethod paymentMethod;
        switch (option.get()) {
            case UPI_COLLECT -> {
                String candidate = vpa == null ? "" : vpa.strip();
                if (candidate.length() > 255 || !VPA.matcher(candidate).matches()) {
                    return backToPage(token, Notice.INVALID_VPA);
                }
                paymentMethod = PaymentMethod.upi(UpiFlow.COLLECT, candidate);
            }
            case UPI_INTENT -> paymentMethod = PaymentMethod.upi(UpiFlow.INTENT, null);
            case CARD -> paymentMethod = PaymentMethod.card();
            case NETBANKING -> {
                if (!properties.isKnownBank(bank)) {
                    return backToPage(token, Notice.INVALID_BANK);
                }
                paymentMethod = PaymentMethod.netbanking(bank);
            }
            default -> throw new IllegalStateException("unhandled option " + option.get());
        }
        try {
            checkout.pay(page, paymentMethod, truncate(request.getRemoteAddr(), 45),
                    truncate(request.getHeader("User-Agent"), 512));
        } catch (GatewayException e) {
            return switch (e.code()) {
                // Double submit or a concurrent change: the page shows whatever state the payment is in now.
                case PAYMENT_INVALID_STATE -> backToPage(token, null);
                case UNSUPPORTED_PAYMENT_METHOD, NO_PROVIDER_AVAILABLE -> backToPage(token, Notice.METHOD_UNAVAILABLE);
                default -> {
                    log.warn("Hosted checkout could not start an attempt for session {}: {}", page.session().id(),
                            e.code().code());
                    yield backToPage(token, Notice.TRY_AGAIN);
                }
            };
        }
        return backToPage(token, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> unexpected(Exception e) {
        log.error("Hosted checkout failed", e);
        return html(HttpStatus.INTERNAL_SERVER_ERROR, CheckoutPages.error());
    }

    private static ResponseEntity<String> backToPage(String token, Notice notice) {
        String location = "/checkout/" + token + (notice == null ? "" : "?error=" + notice.param);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).build();
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(HTML).body(body);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
