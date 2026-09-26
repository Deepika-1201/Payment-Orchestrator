package com.payments.gateway.payment.api;

import com.payments.gateway.payment.api.PaymentResponses.AttemptResponse;
import com.payments.gateway.payment.api.PaymentResponses.CustomerResponse;
import com.payments.gateway.payment.api.PaymentResponses.ErrorResponse;
import com.payments.gateway.payment.api.PaymentResponses.NextActionResponse;
import com.payments.gateway.payment.api.PaymentResponses.PaymentResponse;
import com.payments.gateway.payment.api.PaymentResponses.RefundResponse;
import com.payments.gateway.payment.domain.Customer;
import com.payments.gateway.payment.domain.Failure;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.payment.domain.Refund;
import com.payments.gateway.shared.model.NextAction;
import org.springframework.stereotype.Component;

import static com.payments.gateway.shared.web.WireEnums.wire;

/** Renders the public representation of payments and refunds (API responses and webhook payloads). */
@Component
public class PaymentMapper {

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
                payment.version());
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
                refund.providerCode(),
                refund.providerReference(),
                toResponse(refund.failure()),
                refund.createdAt(),
                refund.updatedAt(),
                refund.version());
    }

    private AttemptResponse toResponse(PaymentAttempt attempt) {
        return new AttemptResponse(
                attempt.id(),
                attempt.attemptNumber(),
                wire(attempt.status()),
                wire(attempt.method().type()),
                wire(attempt.method().upiFlow()),
                attempt.method().bankCode(),
                attempt.card() == null ? null : new PaymentResponses.CardResponse(attempt.card().network(), attempt.card().last4()),
                attempt.providerCode(),
                attempt.providerReference(),
                toResponse(attempt.failure()),
                attempt.createdAt());
    }

    private static NextActionResponse toResponse(NextAction action) {
        if (action == null) {
            return null;
        }
        return new NextActionResponse(wire(action.type()), action.url(), action.upiUri(), action.qrPayload(), action.expiresAt());
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
