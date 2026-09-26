package com.payments.gateway.shared.web;

import org.slf4j.MDC;

/** MDC keys used for log correlation. */
public final class Mdc {

    public static final String REQUEST_ID = "request_id";
    public static final String MERCHANT_ID = "merchant_id";
    public static final String PAYMENT_ID = "payment_id";
    public static final String PROVIDER = "provider";

    private Mdc() {
    }

    public static void clear() {
        MDC.remove(REQUEST_ID);
        MDC.remove(MERCHANT_ID);
        MDC.remove(PAYMENT_ID);
        MDC.remove(PROVIDER);
    }

    public static String requestId() {
        return MDC.get(REQUEST_ID);
    }
}
