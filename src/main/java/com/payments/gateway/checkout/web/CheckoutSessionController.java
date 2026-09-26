package com.payments.gateway.checkout.web;

import com.payments.gateway.checkout.CheckoutService;
import com.payments.gateway.idempotency.IdempotencyService;
import com.payments.gateway.merchant.MerchantPrincipal;
import com.payments.gateway.shared.web.GlobalExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/checkout-sessions")
public class CheckoutSessionController {

    public record CreateCheckoutSession(
            @NotBlank @Size(max = 64) String paymentId,
            @Size(max = 2048) @Pattern(regexp = "https?://\\S+", message = "must be an http(s) URL") String returnUrl) {
    }

    public record CheckoutSessionResponse(String id, String object, String paymentId, String url, String returnUrl,
                                          Instant expiresAt, Instant createdAt) {
    }

    private final CheckoutService checkout;
    private final IdempotencyService idempotency;

    public CheckoutSessionController(CheckoutService checkout, IdempotencyService idempotency) {
        this.checkout = checkout;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestAttribute(MerchantPrincipal.ATTRIBUTE) MerchantPrincipal principal,
                                         @RequestHeader(GlobalExceptionHandler.IDEMPOTENCY_HEADER) String idempotencyKey,
                                         @Valid @RequestBody CreateCheckoutSession request) {
        return idempotency.execute(principal.merchantId(), idempotencyKey, "POST /v1/checkout-sessions", request, () -> {
            CheckoutService.Created created = checkout.create(principal.merchantId(), request.paymentId(), request.returnUrl());
            return new IdempotencyService.Result(201, new CheckoutSessionResponse(created.session().id(),
                    "checkout_session", created.session().paymentId(), created.url(), created.session().returnUrl(),
                    created.session().expiresAt(), created.session().createdAt()));
        });
    }
}
