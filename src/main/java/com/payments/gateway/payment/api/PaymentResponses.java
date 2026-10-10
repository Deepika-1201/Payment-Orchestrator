package com.payments.gateway.payment.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Response bodies of the payment API; also the payload of merchant webhooks. */
public final class PaymentResponses {

    private PaymentResponses() {
    }

    public record PaymentResponse(
            String id,
            String object,
            String merchantOrderId,
            long amount,
            String currency,
            String status,
            String captureMethod,
            String description,
            CustomerResponse customer,
            Map<String, String> metadata,
            long amountCaptured,
            long amountRefunded,
            NextActionResponse nextAction,
            AttemptResponse latestAttempt,
            int attemptCount,
            ErrorResponse lastError,
            String cancellationReason,
            Instant expiresAt,
            Instant authorizationExpiresAt,
            Instant createdAt,
            Instant updatedAt,
            long version,
                String mandateId,
                ConversionResponse conversion) {
    }

            public record ConversionResponse(long settledAmount, String settledCurrency, String rate) {
            }

    public record CustomerResponse(String reference, String email, String phone) {
    }

    /** {@code bankTransfer}: where to send a bank transfer, for type {@code bank_transfer} (ADR-038). */
    public record NextActionResponse(String type, String url, String upiUri, String qrPayload, Instant expiresAt,
                                     BankTransferResponse bankTransfer) {
    }

    public record BankTransferResponse(String accountNumber, String ifsc, String beneficiaryName, String bankName,
                                       String vpa) {
    }

    /** A bank transfer that arrived: how much of it paid the payment, and how much went back ({@code returnId}). */
    public record CreditResponse(String id, String object, String paymentId, long amount, String currency, String mode,
                                 String utr, Instant receivedAt, long appliedAmount, long returnedAmount,
                                 String returnId) {
    }

    /** {@code methodProvider}: the wallet or lender; {@code emiPlan}: the card EMI plan the PSP reported (ADR-037). */
    public record AttemptResponse(String id, int attemptNumber, String status, String method, String upiFlow,
                                  String bankCode, String methodProvider, CardResponse card, EmiPlanResponse emiPlan,
                                  String provider, String providerReference, ErrorResponse failure, Instant createdAt) {
    }

    public record CardResponse(String network, String last4) {
    }

    public record EmiPlanResponse(int tenureMonths, Integer interestRateBps, String issuer) {
    }

    public record ErrorResponse(String code, String category, String message) {
    }

    /** {@code creditId}: the bank transfer credit the refund sends money back from (ADR-038). */
    public record RefundResponse(String id, String object, String paymentId, String attemptId, long amount,
                                 String currency, String status, String reason, String merchantRefundId,
                                 String initiatedBy, String creditId, String provider, String providerReference,
                                 ErrorResponse failure, Instant createdAt, Instant updatedAt, long version,
                                 ConversionResponse conversion) {
    }

    /** {@code respondBy}: the PSP's deadline; {@code response}: the merchant's contest or acceptance (ADR-039). */
    public record DisputeResponse(String id, String object, String paymentId, String attemptId, long amount,
                                  String currency, String status, String reason, String provider,
                                  String providerReference, Instant respondBy, MerchantResponseView response,
                                  Instant createdAt, Instant updatedAt, long version, ConversionResponse conversion) {
    }

    public record MerchantResponseView(String type, String status, String statement, List<String> evidenceFileIds,
                                       Instant requestedAt, Instant sentAt, String failureReason) {
    }

    public record EvidenceFileResponse(String id, String object, String disputeId, String category, String fileName,
                                       String contentType, int size, String sha256, Instant createdAt) {
    }

    public record ListResponse<T>(List<T> data) {
    }

    /** {@code nextAction} only while the customer has to authorize; {@code lastError} once the mandate failed. */
    public record MandateResponse(String id, String object, String status, String instrument, String provider,
                                  long maxAmount, String currency, String frequency, Instant startAt, Instant endAt,
                                  String description, MandateCustomerResponse customer, Map<String, String> metadata,
                                  NextActionResponse nextAction, String registrationPaymentId, ErrorResponse lastError,
                                  Instant activatedAt, Instant createdAt, Instant updatedAt, long version) {
    }

    public record MandateCustomerResponse(String reference, String name, String email, String phone) {
    }

    /** {@code notBefore}: earliest execution of the current cycle; {@code cycle} counts the first attempt and retries. */
    public record MandateDebitResponse(String id, String object, String mandateId, String paymentId,
                                       String merchantDebitId, long amount, String currency, String status,
                                       String description, Instant dueAt, Instant notBefore, int cycle,
                                       Instant notifiedAt, ErrorResponse lastError, Instant createdAt,
                                       Instant updatedAt, long version) {
    }
}
