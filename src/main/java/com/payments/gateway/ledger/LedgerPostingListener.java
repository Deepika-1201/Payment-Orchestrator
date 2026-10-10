package com.payments.gateway.ledger;

import com.payments.gateway.ledger.LedgerModel.Leg;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.shared.events.CurrencyConversion;
import com.payments.gateway.shared.events.FundsMovement;
import com.payments.gateway.shared.model.Money;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Posts captures and refunds in the same transaction as the payment state change that caused them. */
@Component
public class LedgerPostingListener {

    private final LedgerService ledger;

    public LedgerPostingListener(LedgerService ledger) {
        this.ledger = ledger;
    }

    @EventListener
    public void on(FundsMovement movement) {
        LedgerAccountType counterparty = movement.amount().inSettlementCurrency()
                ? LedgerAccountType.PSP_RECEIVABLE : LedgerAccountType.FX_CONVERSION;
        Posting posting = switch (movement.type()) {
            case CAPTURE -> new Posting(movement.merchantId(), movement.providerCode(),
                    LedgerTransactionType.PAYMENT_CAPTURED, "ATTEMPT", movement.referenceId(),
                    "capture for payment " + movement.paymentId(), movement.occurredAt(),
                    List.of(Leg.debit(counterparty, movement.amount()),
                            Leg.credit(LedgerAccountType.SALES_CLEARING, movement.amount())));
            case REFUND -> new Posting(movement.merchantId(), movement.providerCode(),
                    LedgerTransactionType.REFUND_SUCCEEDED, "REFUND", movement.referenceId(),
                    "refund for payment " + movement.paymentId(), movement.occurredAt(),
                    List.of(Leg.debit(LedgerAccountType.REFUNDS, movement.amount()),
                            Leg.credit(counterparty, movement.amount())));
            case CHARGEBACK -> new Posting(movement.merchantId(), movement.providerCode(),
                    LedgerTransactionType.CHARGEBACK, "DISPUTE", movement.referenceId(),
                    "chargeback on payment " + movement.paymentId(), movement.occurredAt(),
                    List.of(Leg.debit(LedgerAccountType.CHARGEBACKS, movement.amount()),
                            Leg.credit(counterparty, movement.amount())));
            case CHARGEBACK_REVERSAL -> new Posting(movement.merchantId(), movement.providerCode(),
                    LedgerTransactionType.REVERSAL, "DISPUTE", movement.referenceId(),
                    "dispute won on payment " + movement.paymentId(), movement.occurredAt(),
                    List.of(Leg.debit(counterparty, movement.amount()),
                            Leg.credit(LedgerAccountType.CHARGEBACKS, movement.amount())));
            case CREDIT_RECEIVED -> new Posting(movement.merchantId(), movement.providerCode(),
                    LedgerTransactionType.CREDIT_RECEIVED, "CREDIT", movement.referenceId(),
                    movement.paymentId() == null ? "unmatched bank transfer credit"
                            : "bank transfer credit for payment " + movement.paymentId(), movement.occurredAt(),
                    List.of(Leg.debit(LedgerAccountType.PSP_RECEIVABLE, movement.amount()),
                            Leg.credit(LedgerAccountType.CUSTOMER_FUNDS, movement.amount())));
            case CREDIT_APPLIED -> new Posting(movement.merchantId(), movement.providerCode(),
                    LedgerTransactionType.CREDIT_APPLIED, "ATTEMPT", movement.referenceId(),
                    "bank transfer credits paid payment " + movement.paymentId(), movement.occurredAt(),
                    List.of(Leg.debit(LedgerAccountType.CUSTOMER_FUNDS, movement.amount()),
                            Leg.credit(LedgerAccountType.SALES_CLEARING, movement.amount())));
            case CREDIT_RETURNED -> new Posting(movement.merchantId(), movement.providerCode(),
                    LedgerTransactionType.CREDIT_RETURNED, "REFUND", movement.referenceId(),
                    "bank transfer credit sent back for payment " + movement.paymentId(), movement.occurredAt(),
                    List.of(Leg.debit(LedgerAccountType.CUSTOMER_FUNDS, movement.amount()),
                            Leg.credit(LedgerAccountType.PSP_RECEIVABLE, movement.amount())));
        };
        ledger.post(posting);
    }

        @EventListener
        public void on(CurrencyConversion conversion) {
                List<Leg> legs = new ArrayList<>();
                boolean received = conversion.kind() == CurrencyConversion.Kind.CAPTURE
                                || conversion.kind() == CurrencyConversion.Kind.CHARGEBACK_REVERSAL;
                addLeg(legs, LedgerAccountType.PSP_RECEIVABLE, received ? EntryDirection.DEBIT : EntryDirection.CREDIT,
                                conversion.settled().amount());
                addLeg(legs, LedgerAccountType.FX_CONVERSION, received ? EntryDirection.CREDIT : EntryDirection.DEBIT,
                                conversion.carriedAmount());
                long difference = Math.subtractExact(conversion.settled().amount(), conversion.carriedAmount());
                boolean gain = received ? difference > 0 : difference < 0;
                addLeg(legs, LedgerAccountType.FX_GAIN_LOSS, gain ? EntryDirection.CREDIT : EntryDirection.DEBIT,
                                Math.abs(difference));
                ledger.post(new Posting(conversion.merchantId(), conversion.providerCode(), LedgerTransactionType.FX_CONVERSION,
                                "CONVERSION", conversion.id(), "INR conversion of " + conversion.kind(), conversion.occurredAt(), legs));
        }

        private static void addLeg(List<Leg> legs, LedgerAccountType account, EntryDirection direction, long amount) {
                if (amount > 0) {
                        legs.add(new Leg(account, direction, Money.of(amount, Money.SETTLEMENT_CURRENCY)));
                }
        }
}
