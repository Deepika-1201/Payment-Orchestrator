package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;
import java.time.Instant;

/** {@code attemptId} is sent to the PSP as merchant reference / idempotency key; {@code expiresAt} is the payment's. */
public record InitiatePaymentRequest(String attemptId, String merchantId, Money amount, PaymentMethod method,
                                     CaptureMethod captureMethod, String description, String customerEmail,
                                     String customerPhone, String returnUrl, String clientIp, Instant expiresAt) {

    public InitiatePaymentRequest(String attemptId, String merchantId, Money amount, PaymentMethod method,
                                  CaptureMethod captureMethod, String description, String customerEmail,
                                  String customerPhone, String returnUrl, String clientIp) {
        this(attemptId, merchantId, amount, method, captureMethod, description, customerEmail, customerPhone, returnUrl,
                clientIp, null);
    }
}
