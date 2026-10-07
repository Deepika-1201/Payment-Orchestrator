package com.payments.gateway.payment.application;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.payment.api.PaymentMapper;
import com.payments.gateway.payment.api.PaymentResponses.PaymentResponse;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.routing.RoutingContext;
import com.payments.gateway.routing.RoutingDecision;
import com.payments.gateway.routing.RoutingEngine;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.PaymentMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/** Payment operations for the hosted checkout, in the public API representation. */
@Service
public class PaymentCheckoutService {

    /**
     * The payment, which of the candidate methods can currently be routed for it, and the card EMI tenures offered by
     * the PSPs that card EMI would be routed to.
     */
    public record View(PaymentResponse payment, List<PaymentMethod> routableMethods, List<Integer> emiTenures) {
    }

    private final PaymentService payments;
    private final PaymentMapper mapper;
    private final MerchantDirectory merchants;
    private final RoutingEngine routing;
    private final ProviderRegistry providers;

    public PaymentCheckoutService(PaymentService payments, PaymentMapper mapper, MerchantDirectory merchants,
                                  RoutingEngine routing, ProviderRegistry providers) {
        this.payments = payments;
        this.mapper = mapper;
        this.merchants = merchants;
        this.routing = routing;
        this.providers = providers;
    }

    /**
     * Probes routing with each candidate, and with every wallet or lender that a registered PSP declares for the
     * {@code providerMethods} (ADR-037).
     */
    public View view(String merchantId, String paymentId, List<PaymentMethod> candidates, Set<MethodType> providerMethods) {
        Payment payment = payments.get(merchantId, paymentId);
        if (payment.status() != PaymentStatus.REQUIRES_PAYMENT_METHOD || (candidates.isEmpty() && providerMethods.isEmpty())) {
            return new View(mapper.toResponse(payment), List.of(), List.of());
        }
        List<PaymentMethod> probes = new ArrayList<>(candidates);
        for (MethodType type : providerMethods) {
            providers.all().stream()
                    .map(provider -> provider.capabilities().methods().get(type))
                    .filter(Objects::nonNull)
                    .flatMap(support -> support.providers().stream())
                    .distinct()
                    .sorted()
                    .forEach(code -> probes.add(new PaymentMethod(type, null, null, null, null, code)));
        }
        Set<String> linked = merchants.activeProviders(merchantId);
        List<PaymentMethod> routable = new ArrayList<>();
        SortedSet<Integer> emiTenures = new TreeSet<>();
        for (PaymentMethod probe : probes) {
            RoutingDecision decision = routing.route(new RoutingContext(merchantId, probe, payment.amount(),
                    payment.captureMethod(), linked, payment.customer().phone() != null));
            if (decision.isEmpty()) {
                continue;
            }
            routable.add(probe);
            if (probe.type() == MethodType.EMI) {
                decision.providers().forEach(code -> emiTenures.addAll(
                        providers.require(code).capabilities().methods().get(MethodType.EMI).tenures()));
            }
        }
        return new View(mapper.toResponse(payment), routable, List.copyOf(emiTenures));
    }

    public PaymentResponse confirm(String merchantId, String paymentId, PaymentMethod method, String returnUrl,
                                   String clientIp, String userAgent) {
        return mapper.toResponse(payments.confirm(merchantId, paymentId,
                new PaymentService.ConfirmCommand(method, returnUrl, clientIp, userAgent, null)));
    }
}
