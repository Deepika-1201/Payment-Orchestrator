package com.payments.gateway.payment.application;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.payment.api.PaymentMapper;
import com.payments.gateway.payment.api.PaymentResponses.PaymentResponse;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.routing.RoutingContext;
import com.payments.gateway.routing.RoutingEngine;
import com.payments.gateway.shared.model.PaymentMethod;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Payment operations for the hosted checkout, in the public API representation. */
@Service
public class PaymentCheckoutService {

    /** The payment and which of the candidate methods can currently be routed for it. */
    public record View(PaymentResponse payment, List<PaymentMethod> routableMethods) {
    }

    private final PaymentService payments;
    private final PaymentMapper mapper;
    private final MerchantDirectory merchants;
    private final RoutingEngine routing;

    public PaymentCheckoutService(PaymentService payments, PaymentMapper mapper, MerchantDirectory merchants,
                                  RoutingEngine routing) {
        this.payments = payments;
        this.mapper = mapper;
        this.merchants = merchants;
        this.routing = routing;
    }

    public View view(String merchantId, String paymentId, List<PaymentMethod> candidates) {
        Payment payment = payments.get(merchantId, paymentId);
        if (payment.status() != PaymentStatus.REQUIRES_PAYMENT_METHOD || candidates.isEmpty()) {
            return new View(mapper.toResponse(payment), List.of());
        }
        Set<String> linked = merchants.activeProviders(merchantId);
        List<PaymentMethod> routable = candidates.stream()
                .filter(method -> !routing.route(new RoutingContext(merchantId, method, payment.amount(),
                        payment.captureMethod(), linked)).isEmpty())
                .toList();
        return new View(mapper.toResponse(payment), routable);
    }

    public PaymentResponse confirm(String merchantId, String paymentId, PaymentMethod method, String returnUrl,
                                   String clientIp, String userAgent) {
        return mapper.toResponse(payments.confirm(merchantId, paymentId,
                new PaymentService.ConfirmCommand(method, returnUrl, clientIp, userAgent, null)));
    }
}
