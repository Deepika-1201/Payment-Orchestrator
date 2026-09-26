package com.payments.gateway.payment.application;

import com.payments.gateway.payment.api.PaymentMapper;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentEvent;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.shared.events.MerchantEventRequested;
import java.time.Clock;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes merchant-visible events synchronously inside the current transaction (outbox recording). */
@Component
public class PaymentEventPublisher {

    private final ApplicationEventPublisher publisher;
    private final PaymentMapper mapper;
    private final Clock clock;

    public PaymentEventPublisher(ApplicationEventPublisher publisher, PaymentMapper mapper, Clock clock) {
        this.publisher = publisher;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void publishPaymentEvents(Payment payment, List<PaymentEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        Object payload = mapper.toResponse(payment);
        for (PaymentEvent event : events) {
            publisher.publishEvent(new MerchantEventRequested(payment.merchantId(), event.type().wireName(),
                    payment.id(), payload, clock.instant()));
        }
    }

    public void publishRefundEvents(Refund refund, List<PaymentEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        Object payload = mapper.toResponse(refund);
        for (PaymentEvent event : events) {
            publisher.publishEvent(new MerchantEventRequested(refund.merchantId(), event.type().wireName(),
                    refund.id(), payload, clock.instant()));
        }
    }
}
