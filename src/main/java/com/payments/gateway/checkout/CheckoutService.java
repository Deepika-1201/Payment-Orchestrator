package com.payments.gateway.checkout;

import com.payments.gateway.merchant.Merchant;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.payment.api.PaymentResponses.PaymentResponse;
import com.payments.gateway.payment.application.PaymentCheckoutService;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.PaymentMethod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Hosted checkout sessions (ADR-013): a merchant creates a session for a payment and redirects the customer to its
 * URL. The random token in the URL is the only credential, so it is stored hashed and scoped to one payment.
 */
@Service
public class CheckoutService {

    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Duration RETENTION_AFTER_EXPIRY = Duration.ofDays(7);

    public record Created(CheckoutSession session, String url) {
    }

    /** Everything one render of the hosted page needs. */
    public record Page(CheckoutSession session, String token, String merchantName, PaymentResponse payment,
                       List<CheckoutOption> options) {
    }

    private final CheckoutSessionRepository repository;
    private final PaymentCheckoutService payments;
    private final MerchantDirectory merchants;
    private final CheckoutProperties properties;
    private final Clock clock;

    public CheckoutService(CheckoutSessionRepository repository, PaymentCheckoutService payments,
                           MerchantDirectory merchants, CheckoutProperties properties, Clock clock) {
        this.repository = repository;
        this.payments = payments;
        this.merchants = merchants;
        this.properties = properties;
        this.clock = clock;
    }

    public Created create(String merchantId, String paymentId, String returnUrl) {
        PaymentResponse payment = payments.view(merchantId, paymentId, List.of()).payment();
        if (!"requires_payment_method".equals(payment.status())) {
            throw GatewayException.invalidState(
                    "A checkout session needs a payment awaiting a payment method; this payment is " + payment.status());
        }
        String token = Hashing.randomToken(32);
        CheckoutSession session = new CheckoutSession(Ids.newId("cs"), merchantId, paymentId, returnUrl,
                payment.expiresAt().plus(properties.resultTtl()), clock.instant());
        repository.insert(session, Hashing.sha256(token));
        return new Created(session, pageUrl(token));
    }

    /** Empty when the token is malformed, unknown or expired, or the merchant is no longer active. */
    public Optional<Page> open(String token) {
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<CheckoutSession> found = repository.findByTokenHash(Hashing.sha256(token))
                .filter(session -> now.isBefore(session.expiresAt()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CheckoutSession session = found.get();
        Merchant merchant = merchants.require(session.merchantId());
        if (merchant.status() != Merchant.Status.ACTIVE) {
            return Optional.empty();
        }
        PaymentCheckoutService.View view = payments.view(merchant.id(), session.paymentId(), CheckoutOption.probes());
        List<CheckoutOption> options = Arrays.stream(CheckoutOption.values())
                .filter(option -> view.routableMethods().contains(option.routingProbe()))
                .toList();
        return Optional.of(new Page(session, token, merchant.name(), view.payment(), options));
    }

    /** Starts an attempt; the PSP sends the customer back to this session's page. */
    public void pay(Page page, PaymentMethod method, String clientIp, String userAgent) {
        payments.confirm(page.session().merchantId(), page.session().paymentId(), method, pageUrl(page.token()),
                clientIp, userAgent);
    }

    public int purgeExpired(int limit) {
        return repository.deleteExpiredBefore(clock.instant().minus(RETENTION_AFTER_EXPIRY), limit);
    }

    private String pageUrl(String token) {
        return properties.publicBaseUrl() + "/checkout/" + token;
    }
}
