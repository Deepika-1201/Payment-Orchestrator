package com.payments.gateway.payment.api;

import com.payments.gateway.payment.api.PaymentResponses.AttemptResponse;
import com.payments.gateway.payment.api.PaymentResponses.BankTransferResponse;
import com.payments.gateway.payment.api.PaymentResponses.ConversionResponse;
import com.payments.gateway.payment.api.PaymentResponses.CreditResponse;
import com.payments.gateway.payment.api.PaymentResponses.CustomerResponse;
import com.payments.gateway.payment.api.PaymentResponses.DisputeResponse;
import com.payments.gateway.payment.api.PaymentResponses.EmiPlanResponse;
import com.payments.gateway.payment.api.PaymentResponses.ErrorResponse;
import com.payments.gateway.payment.api.PaymentResponses.EvidenceFileResponse;
import com.payments.gateway.payment.api.PaymentResponses.MandateCustomerResponse;
import com.payments.gateway.payment.api.PaymentResponses.MandateDebitResponse;
import com.payments.gateway.payment.api.PaymentResponses.MandateResponse;
import com.payments.gateway.payment.api.PaymentResponses.MerchantResponseView;
import com.payments.gateway.payment.api.PaymentResponses.NextActionResponse;
import com.payments.gateway.payment.api.PaymentResponses.PaymentResponse;
import com.payments.gateway.payment.api.PaymentResponses.RefundResponse;
import com.payments.gateway.payment.application.PaymentConversionService;
import com.payments.gateway.payment.domain.Customer;
import com.payments.gateway.payment.domain.Dispute;
import com.payments.gateway.payment.domain.EvidenceFile;
import com.payments.gateway.payment.domain.Failure;
import com.payments.gateway.payment.domain.Mandate;
import com.payments.gateway.payment.domain.MandateCustomer;
import com.payments.gateway.payment.domain.MandateDebit;
import com.payments.gateway.payment.domain.MerchantResponse;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.payment.domain.TransferCredit;
import com.payments.gateway.shared.events.CurrencyConversion.Kind;
import com.payments.gateway.shared.model.BankTransferDetails;
import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.EmiPlan;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import org.springframework.stereotype.Component;

import static com.payments.gateway.shared.web.WireEnums.wire;

/** Renders the public representation of payments, refunds and disputes (API responses and webhook payloads). */
@Component
public class PaymentMapper {

    private final PaymentConversionService conversions;

    public PaymentMapper(PaymentConversionService conversions) {
        this.conversions = conversions;
    }

    public PaymentResponse toResponse(Payment payment) {
        PaymentAttempt latest = payment.latestAttempt();
        NextActionResponse nextAction = payment.status() == PaymentStatus.REQUIRES_ACTION && latest != null
                ? toResponse(latest.nextAction())
                : null;
        ErrorResponse lastError = payment.failureCode() == null ? null
                : new ErrorResponse(payment.failureCode(), latest == null || latest.failure() == null ? null
                        : wire(latest.failure().category()), payment.failureMessage());
        return new PaymentResponse(
                payment.id(),
                "payment",
                payment.merchantOrderId(),
                payment.amount().amount(),
                payment.amount().currency(),
                wire(payment.status()),
                wire(payment.captureMethod()),
                payment.description(),
                toResponse(payment.customer()),
                payment.metadata().isEmpty() ? null : payment.metadata(),
                payment.amountCaptured(),
                payment.amountRefunded(),
                nextAction,
                latest == null ? null : toResponse(latest),
                payment.attempts().size(),
                lastError,
                payment.cancellationReason(),
                payment.expiresAt(),
                payment.authorizationExpiresAt(),
                payment.createdAt(),
                payment.updatedAt(),
                payment.version(),
                payment.mandateId(),
                conversion(payment.amount(), Kind.CAPTURE, payment.succeededAttemptId()));
    }

    public MandateResponse toResponse(Mandate mandate) {
        MandateCustomer customer = mandate.customer();
        return new MandateResponse(
                mandate.id(),
                "mandate",
                wire(mandate.status()),
                wire(mandate.instrument()),
                mandate.providerCode(),
                mandate.maxAmount().amount(),
                mandate.maxAmount().currency(),
                wire(mandate.frequency()),
                mandate.startAt(),
                mandate.endAt(),
                mandate.description(),
                new MandateCustomerResponse(customer.reference(), customer.name(), customer.email(), customer.phone()),
                mandate.metadata().isEmpty() ? null : mandate.metadata(),
                toResponse(mandate.nextAction()),
                mandate.registrationPaymentId(),
                mandate.failureCode() == null ? null : new ErrorResponse(mandate.failureCode(), null, mandate.failureMessage()),
                mandate.activatedAt(),
                mandate.createdAt(),
                mandate.updatedAt(),
                mandate.version());
    }

    public MandateDebitResponse toResponse(MandateDebit debit) {
        return new MandateDebitResponse(
                debit.id(),
                "mandate_debit",
                debit.mandateId(),
                debit.paymentId(),
                debit.merchantDebitId(),
                debit.amount().amount(),
                debit.amount().currency(),
                wire(debit.status()),
                debit.description(),
                debit.dueAt(),
                debit.notBefore(),
                debit.cycle(),
                debit.notifiedAt(),
                debit.failureCode() == null ? null : new ErrorResponse(debit.failureCode(), null, debit.failureMessage()),
                debit.createdAt(),
                debit.updatedAt(),
                debit.version());
    }

    public RefundResponse toResponse(Refund refund) {
        return new RefundResponse(
                refund.id(),
                "refund",
                refund.paymentId(),
                refund.attemptId(),
                refund.amount().amount(),
                refund.amount().currency(),
                wire(refund.status()),
                refund.reason(),
                refund.merchantRefundId(),
                wire(refund.initiatedBy()),
                refund.creditId(),
                refund.providerCode(),
                refund.providerReference(),
                toResponse(refund.failure()),
                refund.createdAt(),
                refund.updatedAt(),
                refund.version(),
                conversion(refund.amount(), Kind.REFUND, refund.id()));
    }

    public CreditResponse toResponse(TransferCredit credit) {
        return new CreditResponse(credit.id(), "credit", credit.paymentId(), credit.amount().amount(),
                credit.amount().currency(), credit.mode() == null ? null : credit.mode().toLowerCase(java.util.Locale.ROOT),
                credit.utr(), credit.receivedAt(), credit.appliedAmount(), credit.returnedAmount(), credit.returnRefundId());
    }

    public DisputeResponse toResponse(Dispute dispute) {
        return new DisputeResponse(
                dispute.id(),
                "dispute",
                dispute.paymentId(),
                dispute.attemptId(),
                dispute.amount().amount(),
                dispute.amount().currency(),
                wire(dispute.status()),
                dispute.reason(),
                dispute.providerCode(),
                dispute.providerDisputeId(),
                dispute.respondBy(),
                toResponse(dispute.response()),
                dispute.createdAt(),
                dispute.updatedAt(),
                dispute.version(),
                conversion(dispute.amount(), Kind.CHARGEBACK, dispute.id()));
    }

    public EvidenceFileResponse toResponse(EvidenceFile file) {
        return new EvidenceFileResponse(file.id(), "dispute_evidence_file", file.disputeId(), wire(file.category()),
                file.fileName(), file.contentType(), file.size(), file.sha256(), file.createdAt());
    }

    private ConversionResponse conversion(Money amount, Kind kind, String referenceId) {
        if (amount.inSettlementCurrency()) {
            return null;
        }
        return conversions.find(kind, referenceId).map(conversion -> new ConversionResponse(
                conversion.settled().amount(), conversion.settled().currency(),
                conversion.rate() == null ? null : conversion.rate().toPlainString())).orElse(null);
    }

    private static MerchantResponseView toResponse(MerchantResponse response) {
        return response == null ? null : new MerchantResponseView(wire(response.type()), wire(response.status()),
                response.statement(), response.fileIds(), response.requestedAt(), response.sentAt(), response.failure());
    }

    private AttemptResponse toResponse(PaymentAttempt attempt) {
        CardDetails card = attempt.card();
        EmiPlan plan = card == null ? null : card.emiPlan();
        return new AttemptResponse(
                attempt.id(),
                attempt.attemptNumber(),
                wire(attempt.status()),
                wire(attempt.method().type()),
                wire(attempt.method().upiFlow()),
                attempt.method().bankCode(),
                attempt.method().provider(),
                card == null ? null : new PaymentResponses.CardResponse(card.network(), card.last4()),
                plan == null ? null : new EmiPlanResponse(plan.tenureMonths(), plan.interestRateBps(), plan.issuer()),
                attempt.providerCode(),
                attempt.providerReference(),
                toResponse(attempt.failure()),
                attempt.createdAt());
    }

    private static NextActionResponse toResponse(NextAction action) {
        if (action == null) {
            return null;
        }
        BankTransferDetails bank = action.bankTransfer();
        return new NextActionResponse(wire(action.type()), action.url(), action.upiUri(), action.qrPayload(), action.expiresAt(),
                bank == null ? null : new BankTransferResponse(bank.accountNumber(), bank.ifsc(), bank.beneficiaryName(),
                        bank.bankName(), bank.vpa()));
    }

    private static ErrorResponse toResponse(Failure failure) {
        return failure == null ? null : new ErrorResponse(failure.code(), wire(failure.category()), failure.message());
    }

    private static CustomerResponse toResponse(Customer customer) {
        if (customer == null || (customer.reference() == null && customer.email() == null && customer.phone() == null)) {
            return null;
        }
        return new CustomerResponse(customer.reference(), customer.email(), customer.phone());
    }
}
