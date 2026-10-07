package com.payments.gateway.checkout.web;

import com.payments.gateway.checkout.CheckoutOption;
import com.payments.gateway.checkout.CheckoutProperties.Bank;
import com.payments.gateway.checkout.CheckoutService.Page;
import com.payments.gateway.payment.api.PaymentResponses.NextActionResponse;
import com.payments.gateway.payment.api.PaymentResponses.PaymentResponse;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.model.Money;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.util.HtmlUtils;

/**
 * Server-rendered hosted checkout pages. No JavaScript and no third-party assets: every dynamic value is
 * HTML-escaped, and the only inline style is allowed by hash in the Content-Security-Policy.
 */
final class CheckoutPages {

    private static final String CSS = "body{font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;"
            + "background:#f4f5f7;color:#1d2230;margin:0}main{max-width:420px;margin:32px auto;background:#fff;"
            + "border-radius:12px;padding:24px;box-shadow:0 1px 4px rgba(0,0,0,.08)}h1{font-size:1.2rem;margin:0 0 12px}"
            + ".merchant{font-weight:600}.amount{font-size:1.8rem;font-weight:600;margin:4px 0 8px}"
            + ".muted{color:#5f6675;font-size:.9rem}form{border-top:1px solid #e6e8ec;margin-top:16px;padding-top:16px}"
            + "label{display:block;font-weight:600;margin-bottom:8px}input,select{width:100%;box-sizing:border-box;"
            + "padding:10px;border:1px solid #c9ced6;border-radius:8px;font-size:1rem;margin-bottom:10px}"
            + "button,.button{display:block;width:100%;box-sizing:border-box;padding:12px;border:0;border-radius:8px;"
            + "background:#1f5eff;color:#fff;font-size:1rem;text-align:center;text-decoration:none;cursor:pointer;"
            + "margin-top:8px}.notice{background:#fdecec;color:#8a1c1c;padding:10px;border-radius:8px;margin:12px 0}"
            + ".ok{color:#11633a}.bad{color:#8a1c1c}.qr{text-align:center;margin:16px 0}";

    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'sha256-"
            + Base64.getEncoder().encodeToString(Hashing.sha256(CSS)) + "'; form-action 'self'; frame-ancestors 'none'; "
            + "base-uri 'none'";

    /** Display names of the wallet, lender and pay-later codes the PSPs use; other codes are shown as they are. */
    private static final Map<String, String> PROVIDER_NAMES = Map.ofEntries(
            Map.entry("phonepe", "PhonePe"), Map.entry("amazonpay", "Amazon Pay"), Map.entry("mobikwik", "MobiKwik"),
            Map.entry("payzapp", "PayZapp"), Map.entry("paytm", "Paytm"), Map.entry("olamoney", "Ola Money"),
            Map.entry("airtelmoney", "Airtel Money"), Map.entry("jiomoney", "JioMoney"), Map.entry("zestmoney", "ZestMoney"),
            Map.entry("earlysalary", "Fibe"), Map.entry("walnut369", "axio"), Map.entry("hdfc", "HDFC Bank"),
            Map.entry("icic", "ICICI Bank"), Map.entry("idfb", "IDFC FIRST Bank"), Map.entry("kkbk", "Kotak Mahindra Bank"),
            Map.entry("lazypay", "LazyPay"), Map.entry("simpl", "Simpl"));

    /** Fixed, parameter-selected messages: nothing from the query string is ever echoed into the page. */
    enum Notice {
        INVALID_VPA("invalid_vpa", "Enter a valid UPI ID, for example name@bank."),
        INVALID_BANK("invalid_bank", "Choose your bank from the list."),
        METHOD_UNAVAILABLE("method_unavailable", "That payment method is not available right now. Please choose another."),
        TRY_AGAIN("try_again", "We could not start the payment. Please try again.");

        final String param;
        final String message;

        Notice(String param, String message) {
            this.param = param;
            this.message = message;
        }

        static Notice fromParam(String value) {
            return Arrays.stream(values()).filter(notice -> notice.param.equals(value)).findFirst().orElse(null);
        }
    }

    private CheckoutPages() {
    }

    static String render(Page page, List<Bank> banks, Notice notice) {
        PaymentResponse payment = page.payment();
        StringBuilder body = new StringBuilder(header(page));
        Integer refreshSeconds = null;
        switch (payment.status()) {
            case "requires_payment_method" -> {
                if (notice != null) {
                    body.append(notice(notice.message));
                } else if (payment.lastError() != null) {
                    body.append(notice("Your previous attempt was not successful. Try again or choose another method."));
                }
                body.append(options(page, banks));
            }
            case "requires_action" -> refreshSeconds = nextAction(body, payment.nextAction());
            case "processing" -> {
                body.append(paragraph("Processing your payment. This page updates automatically."));
                refreshSeconds = 3;
            }
            case "succeeded" -> body.append(outcome("Payment successful", true)).append(returnLink(page));
            case "authorized" -> body.append(outcome("Payment authorized", true)).append(returnLink(page));
            case "failed" -> body.append(outcome("Payment failed", false)).append(returnLink(page));
            case "cancelled" -> body.append(outcome("Payment cancelled", false)).append(returnLink(page));
            case "expired" -> body.append(outcome("This payment has expired", false)).append(returnLink(page));
            default -> body.append(paragraph("Payment status: " + payment.status()));
        }
        body.append("<p class=\"muted\">Payment ").append(escape(payment.id())).append("</p>");
        return document("Pay " + page.merchantName(), refreshSeconds, body.toString());
    }

    static String notFound() {
        return document("Payment link unavailable", null,
                "<h1>Payment link unavailable</h1>" + paragraph("This payment link is invalid or has expired."));
    }

    static String error() {
        return document("Something went wrong", null,
                "<h1>Something went wrong</h1>" + paragraph("Please try again in a moment."));
    }

    private static String header(Page page) {
        PaymentResponse payment = page.payment();
        String amount = Money.of(payment.amount(), payment.currency()).toDecimalString();
        String symbol = "INR".equals(payment.currency()) ? "\u20B9" : payment.currency() + " ";
        StringBuilder html = new StringBuilder()
                .append("<p class=\"merchant\">").append(escape(page.merchantName())).append("</p>")
                .append("<div class=\"amount\">").append(escape(symbol + amount)).append("</div>");
        String details = payment.description() == null ? "Order " + payment.merchantOrderId()
                : payment.description() + " \u00B7 Order " + payment.merchantOrderId();
        return html.append("<p class=\"muted\">").append(escape(details)).append("</p>").toString();
    }

    private static String options(Page page, List<Bank> banks) {
        if (page.options().isEmpty()) {
            return paragraph("No payment methods are available right now. Please try again later.");
        }
        StringBuilder html = new StringBuilder();
        for (CheckoutOption option : page.options()) {
            html.append("<form method=\"post\" action=\"/checkout/").append(escape(page.token())).append("\">")
                    .append("<input type=\"hidden\" name=\"method\" value=\"").append(option.formValue()).append("\">");
            switch (option) {
                case UPI_COLLECT -> html
                        .append("<label for=\"vpa\">UPI ID</label>")
                        .append("<input id=\"vpa\" name=\"vpa\" type=\"text\" required maxlength=\"255\" ")
                        .append("autocomplete=\"off\" autocapitalize=\"none\" spellcheck=\"false\" placeholder=\"name@bank\">")
                        .append("<button type=\"submit\">Send payment request</button>");
                case UPI_INTENT -> html
                        .append("<label>UPI app</label>")
                        .append("<button type=\"submit\">Pay with a UPI app on this phone</button>");
                case UPI_QR -> html
                        .append("<label>UPI QR</label>")
                        .append("<button type=\"submit\">Show a QR code to scan with your phone</button>");
                case CARD -> html
                        .append("<label>Card</label>")
                        .append("<button type=\"submit\">Pay by card</button>")
                        .append("<p class=\"muted\">You will enter your card details on our payment partner's secure page.</p>");
                case NETBANKING -> {
                    html.append("<label for=\"bank\">Netbanking</label><select id=\"bank\" name=\"bank\" required>")
                            .append("<option value=\"\">Choose your bank</option>");
                    for (Bank bank : banks) {
                        html.append("<option value=\"").append(escape(bank.code())).append("\">")
                                .append(escape(bank.name())).append("</option>");
                    }
                    html.append("</select><button type=\"submit\">Pay with netbanking</button>");
                }
                case WALLET -> providers(html, page, option, "Wallet", "Choose your wallet", "Pay with wallet");
                case EMI -> html
                        .append("<label>EMI on a credit or debit card</label>")
                        .append("<button type=\"submit\">Pay in EMIs</button>")
                        .append("<p class=\"muted\">").append(escape(tenures(page.emiTenures())))
                        .append(" You choose your bank and plan on our payment partner's secure page.</p>");
                case CARDLESS_EMI -> providers(html, page, option, "Cardless EMI", "Choose a lender", "Pay with cardless EMI");
                case PAY_LATER -> providers(html, page, option, "Pay later", "Choose a provider", "Pay later");
            }
            html.append("</form>");
        }
        return html.toString();
    }

    private static void providers(StringBuilder html, Page page, CheckoutOption option, String label, String prompt,
                                  String button) {
        String id = option.formValue() + "_provider";
        html.append("<label for=\"").append(id).append("\">").append(label).append("</label><select id=\"").append(id)
                .append("\" name=\"provider\" required><option value=\"\">").append(prompt).append("</option>");
        for (String provider : page.providers().getOrDefault(option, List.of())) {
            html.append("<option value=\"").append(escape(provider)).append("\">")
                    .append(escape(PROVIDER_NAMES.getOrDefault(provider, provider))).append("</option>");
        }
        html.append("</select><button type=\"submit\">").append(button).append("</button>");
    }

    private static String tenures(List<Integer> months) {
        if (months.isEmpty()) {
            return "";
        }
        if (months.size() == 1) {
            return "A " + months.getFirst() + "-month plan is available.";
        }
        String list = months.subList(0, months.size() - 1).stream().map(String::valueOf).collect(Collectors.joining(", "));
        return "Plans of " + list + " or " + months.getLast() + " months are available.";
    }

    /** Renders the customer's next step and returns the auto-refresh interval. */
    private static Integer nextAction(StringBuilder body, NextActionResponse action) {
        if (action == null) {
            body.append(paragraph("Processing your payment. This page updates automatically."));
            return 3;
        }
        switch (action.type()) {
            case "redirect" -> {
                if (isHttpUrl(action.url())) {
                    body.append(paragraph("Continue on the secure payment page to complete your payment."))
                            .append("<a class=\"button\" href=\"").append(escape(action.url())).append("\">Continue</a>")
                            .append("<p class=\"muted\">Already paid? This page updates automatically.</p>");
                } else {
                    body.append(paragraph("Complete the payment on the payment page you were shown."));
                }
            }
            case "upi_intent" -> {
                if (action.upiUri() != null && action.upiUri().startsWith("upi://")) {
                    body.append("<a class=\"button\" href=\"").append(escape(action.upiUri())).append("\">Open UPI app</a>");
                }
                body.append("<p class=\"muted\">After paying in your UPI app, come back here. This page updates automatically.</p>");
            }
            case "await_approval" -> body.append(paragraph("Open your UPI app and approve the payment request."))
                    .append("<p class=\"muted\">This page updates automatically.</p>");
            case "display_qr" -> {
                String qr = action.qrPayload() != null && action.qrPayload().startsWith("upi://")
                        ? QrSvg.render(action.qrPayload(), "UPI QR code for this payment").orElse(null)
                        : null;
                if (qr == null) {
                    body.append(paragraph("Complete the payment in your UPI app. This page updates automatically."));
                } else {
                    body.append(paragraph("Scan this code with any UPI app to pay.")).append("<div class=\"qr\">")
                            .append(qr).append("</div><p class=\"muted\">This page updates automatically once you have paid.</p>");
                }
            }
            default -> body.append(paragraph("Complete the payment in your UPI app. This page updates automatically."));
        }
        return 5;
    }

    private static String returnLink(Page page) {
        String url = page.session().returnUrl();
        if (!isHttpUrl(url)) {
            return "";
        }
        return "<a class=\"button\" href=\"" + escape(url) + "\">Return to " + escape(page.merchantName()) + "</a>";
    }

    private static String outcome(String text, boolean success) {
        return "<h1 class=\"" + (success ? "ok" : "bad") + "\">" + escape(text) + "</h1>";
    }

    private static String notice(String text) {
        return "<div class=\"notice\" role=\"alert\">" + escape(text) + "</div>";
    }

    private static String paragraph(String text) {
        return "<p>" + escape(text) + "</p>";
    }

    private static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    private static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    private static String document(String title, Integer refreshSeconds, String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex,nofollow\">"
                + (refreshSeconds == null ? "" : "<meta http-equiv=\"refresh\" content=\"" + refreshSeconds + "\">")
                + "<title>" + escape(title) + "</title><style>" + CSS + "</style></head><body><main>" + body
                + "</main></body></html>";
    }
}
