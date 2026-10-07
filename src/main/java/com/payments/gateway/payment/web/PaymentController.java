package com.payments.gateway.payment.web;

import com.payments.gateway.idempotency.IdempotencyService;
import com.payments.gateway.merchant.MerchantPrincipal;
import com.payments.gateway.payment.api.PaymentMapper;
import com.payments.gateway.payment.api.PaymentRequests;
import com.payments.gateway.payment.api.PaymentResponses.PaymentResponse;
import com.payments.gateway.payment.application.PaymentService;
import com.payments.gateway.payment.domain.Customer;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
import com.payments.gateway.shared.web.GlobalExceptionHandler;
import com.payments.gateway.shared.web.WireEnums;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final PaymentMapper mapper;

    public PaymentController(PaymentService payments, IdempotencyService idempotency, PaymentMapper mapper) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                         @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                         @Valid @RequestBody PaymentRequests.CreatePayment request) {
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/payments", request,
                () -> new IdempotencyService.Result(201,
                        mapper.toResponse(payments.create(principal.merchantId(), toCommand(request)))));
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                               @PathVariable String paymentId) {
        return mapper.toResponse(payments.get(principal.merchantId(), paymentId));
    }

    @PostMapping("/{paymentId}/confirm")
    public ResponseEntity<String> confirm(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                          @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                          @PathVariable String paymentId,
                                          @Valid @RequestBody PaymentRequests.ConfirmPayment request) {
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/payments/" + paymentId + "/confirm",
                request, () -> new IdempotencyService.Result(200,
                        mapper.toResponse(payments.confirm(principal.merchantId(), paymentId, toCommand(request)))));
    }

    @PostMapping("/{paymentId}/capture")
    public ResponseEntity<String> capture(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                          @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                          @PathVariable String paymentId,
                                          @Valid @RequestBody(required = false) PaymentRequests.CapturePayment request) {
        Long amount = request == null ? null : request.amount();
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/payments/" + paymentId + "/capture",
                request, () -> new IdempotencyService.Result(200,
                        mapper.toResponse(payments.capture(principal.merchantId(), paymentId, amount))));
    }

    @PostMapping("/{paymentId}/cancel")
    public ResponseEntity<String> cancel(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                         @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                         @PathVariable String paymentId,
                                         @Valid @RequestBody(required = false) PaymentRequests.CancelPayment request) {
        String reason = request == null ? null : request.reason();
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/payments/" + paymentId + "/cancel",
                request, () -> new IdempotencyService.Result(200,
                        mapper.toResponse(payments.cancel(principal.merchantId(), paymentId, reason))));
    }

    private static PaymentService.CreateCommand toCommand(PaymentRequests.CreatePayment request) {
        CaptureMethod captureMethod = request.captureMethod() == null ? CaptureMethod.AUTOMATIC
                : WireEnums.parse(CaptureMethod.class, request.captureMethod(), "capture_method");
        PaymentRequests.Customer customer = request.customer();
        return new PaymentService.CreateCommand(
                Money.of(request.amount(), request.currency()),
                request.merchantOrderId(),
                captureMethod,
                request.description(),
                customer == null ? Customer.NONE : new Customer(customer.reference(), customer.email(), customer.phone()),
                request.metadata(),
                request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds()));
    }

    private static PaymentService.ConfirmCommand toCommand(PaymentRequests.ConfirmPayment request) {
        PaymentRequests.PaymentMethod requested = request.paymentMethod();
        MethodType type = WireEnums.parse(MethodType.class, requested.type(), "payment_method.type");
        PaymentMethod method;
        try {
            method = switch (type) {
                case UPI -> {
                    if (requested.upi() == null) {
                        throw GatewayException.validation("payment_method.upi", "is required for UPI payments");
                    }
                    yield PaymentMethod.upi(WireEnums.parse(UpiFlow.class, requested.upi().flow(), "payment_method.upi.flow"),
                            requested.upi().vpa());
                }
                case CARD -> PaymentMethod.card();
                case NETBANKING -> {
                    if (requested.netbanking() == null) {
                        throw GatewayException.validation("payment_method.netbanking", "is required for netbanking payments");
                    }
                    yield PaymentMethod.netbanking(requested.netbanking().bankCode());
                }
                case MANDATE -> throw GatewayException.validation("payment_method.type",
                        "mandate debits are created with POST /v1/mandates/{id}/debits");
            };
        } catch (IllegalArgumentException e) {
            throw GatewayException.validation("payment_method", e.getMessage());
        }
        PaymentRequests.Client client = request.client();
        return new PaymentService.ConfirmCommand(method, request.returnUrl(),
                client == null ? null : client.ip(),
                client == null ? null : client.userAgent(),
                client == null ? null : client.deviceId());
    }
}
