package com.payments.gateway.payment.web;

import com.payments.gateway.idempotency.IdempotencyService;
import com.payments.gateway.merchant.MerchantPrincipal;
import com.payments.gateway.payment.api.PaymentMapper;
import com.payments.gateway.payment.api.PaymentRequests;
import com.payments.gateway.payment.api.PaymentResponses.ListResponse;
import com.payments.gateway.payment.api.PaymentResponses.RefundResponse;
import com.payments.gateway.payment.application.RefundService;
import com.payments.gateway.shared.web.GlobalExceptionHandler;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundController {

    private final RefundService refunds;
    private final IdempotencyService idempotency;
    private final PaymentMapper mapper;

    public RefundController(RefundService refunds, IdempotencyService idempotency, PaymentMapper mapper) {
        this.refunds = refunds;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @PostMapping("/v1/payments/{paymentId}/refunds")
    public ResponseEntity<String> create(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                         @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                         @PathVariable String paymentId,
                                         @Valid @RequestBody(required = false) PaymentRequests.CreateRefund request) {
        RefundService.CreateCommand command = request == null
                ? new RefundService.CreateCommand(null, null, null)
                : new RefundService.CreateCommand(request.amount(), request.reason(), request.merchantRefundId());
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/payments/" + paymentId + "/refunds",
                request, () -> new IdempotencyService.Result(201,
                        mapper.toResponse(refunds.create(principal.merchantId(), paymentId, command))));
    }

    @GetMapping("/v1/payments/{paymentId}/refunds")
    public ListResponse<RefundResponse> list(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                             @PathVariable String paymentId) {
        return new ListResponse<>(refunds.listForPayment(principal.merchantId(), paymentId).stream()
                .map(mapper::toResponse)
                .toList());
    }

    @GetMapping("/v1/refunds/{refundId}")
    public RefundResponse get(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                              @PathVariable String refundId) {
        return mapper.toResponse(refunds.get(principal.merchantId(), refundId));
    }
}
