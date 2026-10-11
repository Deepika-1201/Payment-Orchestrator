package com.payments.gateway.billing;

import com.payments.gateway.billing.BillingAmounts.Price;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.events.BillableActivity;
import com.payments.gateway.shared.events.BillableActivity.Kind;
import com.payments.gateway.shared.events.CurrencyConversion;
import com.payments.gateway.shared.model.Money;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BillingActivityListener {

    private final BillingRepository repository;

    public BillingActivityListener(BillingRepository repository) {
        this.repository = repository;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(BillableActivity activity) {
        repository.effective(activity.merchantId(), activity.recognizedAt()).ifPresent(plan -> {
            Price price = (activity.kind() == Kind.PAYMENT ? plan.paymentFees() : plan.refundFees()).get(activity.method());
            if (activity.kind() == Kind.REFUND && price == null) {
                return;
            }
            Money settled = activity.amount().inSettlementCurrency() ? activity.amount() : activity.settled();
            if (settled != null && !settled.inSettlementCurrency()) {
                throw new IllegalArgumentException("billing requires reported INR settlement amounts");
            }
            Long serviceFee = settled == null || price == null ? null : price.charge(settled.amount());
            repository.insertEvent(new BillingEvent(Ids.newId("bev"), activity.merchantId(), activity.kind(), activity.referenceId(),
                    activity.paymentId(), plan.id(), activity.method(), activity.amount(), settled == null ? null : settled.amount(),
                    serviceFee, null, activity.recognizedAt()));
        });
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(CurrencyConversion conversion) {
        Kind kind = switch (conversion.kind()) {
            case CAPTURE -> Kind.PAYMENT;
            case REFUND -> Kind.REFUND;
            default -> null;
        };
        if (kind == null || conversion.referenceId() == null) {
            return;
        }
        repository.event(kind, conversion.referenceId(), true)
                .filter(event -> event.merchantId().equals(conversion.merchantId()) && event.settledAmount() == null)
                .ifPresent(event -> {
                    BillingPlan plan = repository.plan(event.planId());
                    Price price = (kind == Kind.PAYMENT ? plan.paymentFees() : plan.refundFees()).get(event.method());
                    repository.completeEvent(event.id(), conversion.settled().amount(),
                            price == null ? null : price.charge(conversion.settled().amount()), null);
                });
    }
}