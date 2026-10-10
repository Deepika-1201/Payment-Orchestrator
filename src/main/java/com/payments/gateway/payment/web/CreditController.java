package com.payments.gateway.payment.web;

import com.payments.gateway.merchant.MerchantPrincipal;
import com.payments.gateway.payment.api.PaymentMapper;
import com.payments.gateway.payment.api.PaymentResponses.CreditResponse;
import com.payments.gateway.payment.api.PaymentResponses.ListResponse;
import com.payments.gateway.payment.application.TransferCreditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Read-only: the bank transfers that arrived for a payment (ADR-038). */
@RestController
public class CreditController {

    private final TransferCreditService credits;
    private final PaymentMapper mapper;

    public CreditController(TransferCreditService credits, PaymentMapper mapper) {
        this.credits = credits;
        this.mapper = mapper;
    }

    @GetMapping("/v1/payments/{paymentId}/credits")
    public ListResponse<CreditResponse> list(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                             @PathVariable String paymentId) {
        return new ListResponse<>(credits.listForPayment(principal.merchantId(), paymentId).stream()
                .map(mapper::toResponse)
                .toList());
    }
}
