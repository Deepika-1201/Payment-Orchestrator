package com.payments.gateway.payment.web;

import com.payments.gateway.merchant.MerchantPrincipal;
import com.payments.gateway.payment.api.PaymentMapper;
import com.payments.gateway.payment.api.PaymentResponses.DisputeResponse;
import com.payments.gateway.payment.api.PaymentResponses.ListResponse;
import com.payments.gateway.payment.application.DisputeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Read-only: disputes are raised and decided at the PSP (ADR-018). */
@RestController
public class DisputeController {

    private final DisputeService disputes;
    private final PaymentMapper mapper;

    public DisputeController(DisputeService disputes, PaymentMapper mapper) {
        this.disputes = disputes;
        this.mapper = mapper;
    }

    @GetMapping("/v1/payments/{paymentId}/disputes")
    public ListResponse<DisputeResponse> list(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                              @PathVariable String paymentId) {
        return new ListResponse<>(disputes.listForPayment(principal.merchantId(), paymentId).stream()
                .map(mapper::toResponse)
                .toList());
    }

    @GetMapping("/v1/disputes/{disputeId}")
    public DisputeResponse get(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                               @PathVariable String disputeId) {
        return mapper.toResponse(disputes.get(principal.merchantId(), disputeId));
    }
}
