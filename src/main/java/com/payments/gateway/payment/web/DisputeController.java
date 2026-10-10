package com.payments.gateway.payment.web;

import com.payments.gateway.idempotency.IdempotencyService;
import com.payments.gateway.merchant.MerchantPrincipal;
import com.payments.gateway.payment.api.DisputeRequests;
import com.payments.gateway.payment.api.PaymentMapper;
import com.payments.gateway.payment.api.PaymentResponses.DisputeResponse;
import com.payments.gateway.payment.api.PaymentResponses.EvidenceFileResponse;
import com.payments.gateway.payment.api.PaymentResponses.ListResponse;
import com.payments.gateway.payment.application.DisputeResponseService;
import com.payments.gateway.payment.application.DisputeResponseService.Responded;
import com.payments.gateway.payment.application.DisputeService;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.EvidenceCategory;
import com.payments.gateway.shared.web.GlobalExceptionHandler;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import java.util.Base64;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Disputes are raised and decided at the PSP (ADR-018); merchants may contest or accept them here (ADR-039). */
@RestController
public class DisputeController {

    private final DisputeService disputes;
    private final DisputeResponseService responses;
    private final IdempotencyService idempotency;
    private final PaymentMapper mapper;

    public DisputeController(DisputeService disputes, DisputeResponseService responses, IdempotencyService idempotency,
                             PaymentMapper mapper) {
        this.disputes = disputes;
        this.responses = responses;
        this.idempotency = idempotency;
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

    @PostMapping("/v1/disputes/{disputeId}/evidence_files")
    public ResponseEntity<String> upload(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                         @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                         @PathVariable String disputeId,
                                         @Valid @RequestBody DisputeRequests.EvidenceFile request) {
        return idempotency.execute(principal.merchantId(), idempotencyKey,
                "POST /v1/disputes/" + disputeId + "/evidence_files", request,
                () -> new IdempotencyService.Result(201, mapper.toResponse(responses.upload(principal.merchantId(),
                        disputeId, new DisputeResponseService.Upload(
                                WireEnums.parse(EvidenceCategory.class, request.category(), "category"),
                                request.fileName(), request.contentType(), decode(request.contentBase64()))))));
    }

    @GetMapping("/v1/disputes/{disputeId}/evidence_files")
    public ListResponse<EvidenceFileResponse> files(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                                    @PathVariable String disputeId) {
        return new ListResponse<>(responses.listFiles(principal.merchantId(), disputeId).stream()
                .map(mapper::toResponse)
                .toList());
    }

    @PostMapping("/v1/disputes/{disputeId}/contest")
    public ResponseEntity<String> contest(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                          @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                          @PathVariable String disputeId,
                                          @Valid @RequestBody DisputeRequests.Contest request) {
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/disputes/" + disputeId + "/contest",
                request, () -> result(responses.contest(principal.merchantId(), disputeId, request.statement(),
                        request.evidenceFileIds())));
    }

    @PostMapping("/v1/disputes/{disputeId}/accept")
    public ResponseEntity<String> accept(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                         @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                         @PathVariable String disputeId) {
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/disputes/" + disputeId + "/accept",
                null, () -> result(responses.accept(principal.merchantId(), disputeId)));
    }

    /** 200 when the PSP took the response at once, 202 while it is still being delivered. */
    private IdempotencyService.Result result(Responded responded) {
        return new IdempotencyService.Result(responded.sent() ? 200 : 202, mapper.toResponse(responded.dispute()));
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw GatewayException.validation("content_base64", "must be base64");
        }
    }
}
