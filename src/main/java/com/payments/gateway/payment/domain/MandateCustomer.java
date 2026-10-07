package com.payments.gateway.payment.domain;

/** The mandate's customer. Email and phone are required: the PSP sends the mandate and pre-debit notices to them. */
public record MandateCustomer(String reference, String name, String email, String phone) {
}
