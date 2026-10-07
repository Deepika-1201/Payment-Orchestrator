package com.payments.gateway.payment.web;

import com.payments.gateway.idempotency.IdempotencyService;
import com.payments.gateway.merchant.MerchantPrincipal;
import com.payments.gateway.payment.api.MandateRequests;
import com.payments.gateway.payment.api.PaymentMapper;
import com.payments.gateway.payment.api.PaymentResponses.ListResponse;
import com.payments.gateway.payment.api.PaymentResponses.MandateDebitResponse;
import com.payments.gateway.payment.api.PaymentResponses.MandateResponse;
import com.payments.gateway.payment.application.MandateService;
import com.payments.gateway.payment.domain.MandateCustomer;
import com.payments.gateway.shared.model.MandateFrequency;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.web.GlobalExceptionHandler;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mandates and their debits (LLD §18.2). */
@RestController
@RequestMapping("/v1/mandates")
public class MandateController {

    private final MandateService mandates;
    private final IdempotencyService idempotency;
    private final PaymentMapper mapper;

    public MandateController(MandateService mandates, IdempotencyService idempotency, PaymentMapper mapper) {
        this.mandates = mandates;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                         @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                         @Valid @RequestBody MandateRequests.CreateMandate request) {
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/mandates", request,
                () -> new IdempotencyService.Result(201,
                        mapper.toResponse(mandates.create(principal.merchantId(), toCommand(request)))));
    }

    @GetMapping("/{mandateId}")
    public MandateResponse get(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                               @PathVariable String mandateId) {
        return mapper.toResponse(mandates.get(principal.merchantId(), mandateId));
    }

    @PostMapping("/{mandateId}/revoke")
    public ResponseEntity<String> revoke(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                         @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                         @PathVariable String mandateId) {
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/mandates/" + mandateId + "/revoke",
                null, () -> new IdempotencyService.Result(200,
                        mapper.toResponse(mandates.revoke(principal.merchantId(), mandateId))));
    }

    @PostMapping("/{mandateId}/debits")
    public ResponseEntity<String> createDebit(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                              @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                              @PathVariable String mandateId,
                                              @Valid @RequestBody MandateRequests.CreateDebit request) {
        MandateService.DebitCommand command = new MandateService.DebitCommand(request.amount(),
                request.merchantDebitId(), request.dueAt(), request.description());
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/mandates/" + mandateId + "/debits",
                request, () -> new IdempotencyService.Result(201,
                        mapper.toResponse(mandates.createDebit(principal.merchantId(), mandateId, command))));
    }

    @GetMapping("/{mandateId}/debits")
    public ListResponse<MandateDebitResponse> listDebits(
            @RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal, @PathVariable String mandateId) {
        return new ListResponse<>(mandates.listDebits(principal.merchantId(), mandateId).stream()
                .map(mapper::toResponse)
                .toList());
    }

    @GetMapping("/{mandateId}/debits/{debitId}")
    public MandateDebitResponse getDebit(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                         @PathVariable String mandateId, @PathVariable String debitId) {
        return mapper.toResponse(mandates.getDebit(principal.merchantId(), mandateId, debitId));
    }

    @PostMapping("/{mandateId}/debits/{debitId}/cancel")
    public ResponseEntity<String> cancelDebit(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                              @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                              @PathVariable String mandateId, @PathVariable String debitId) {
        return idempotency.execute(principal.merchantId(), idempotencyKey,
                "POST /v1/mandates/" + mandateId + "/debits/" + debitId + "/cancel", null,
                () -> new IdempotencyService.Result(200,
                        mapper.toResponse(mandates.cancelDebit(principal.merchantId(), mandateId, debitId))));
    }

    private static MandateService.CreateCommand toCommand(MandateRequests.CreateMandate request) {
        MandateRequests.Customer customer = request.customer();
        return new MandateService.CreateCommand(
                WireEnums.parse(MandateInstrument.class, request.instrument(), "instrument"),
                Money.of(request.maxAmount(), request.currency()),
                WireEnums.parse(MandateFrequency.class, request.frequency(), "frequency"),
                request.startAt(),
                request.endAt(),
                request.description(),
                new MandateCustomer(customer.reference(), customer.name(), customer.email(), customer.phone()),
                request.metadata(),
                request.returnUrl());
    }
}
