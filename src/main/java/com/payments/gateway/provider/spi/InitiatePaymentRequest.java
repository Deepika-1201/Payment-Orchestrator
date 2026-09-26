package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;

/** {@code attemptId} is sent to the PSP as merchant reference / idempotency key. */
public record InitiatePaymentRequest(String attemptId, String merchantId, Money amount, PaymentMethod method,
                                     CaptureMethod captureMethod, String description, String customerEmail,
                                     String customerPhone, String returnUrl, String clientIp) {
}
