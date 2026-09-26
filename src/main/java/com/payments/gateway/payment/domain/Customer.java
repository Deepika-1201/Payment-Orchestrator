package com.payments.gateway.payment.domain;

public record Customer(String reference, String email, String phone) {

    public static final Customer NONE = new Customer(null, null, null);
}
