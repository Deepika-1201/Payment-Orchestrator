package com.payments.gateway.risk;

import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.PaymentMethod;

public record RiskContext(String merchantId, String paymentId, Money amount, PaymentMethod method,
                          String customerReference, String customerEmail, String ip, String deviceId,
                          int recentAttemptsByCustomer) {
}
