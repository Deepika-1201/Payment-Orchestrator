package com.payments.gateway.provider.spi;

import com.payments.gateway.provider.spi.MandateRequests.CreateMandateRequest;
import com.payments.gateway.provider.spi.MandateRequests.DebitNotificationQuery;
import com.payments.gateway.provider.spi.MandateRequests.DebitNotificationRequest;
import com.payments.gateway.provider.spi.MandateRequests.ExecuteDebitRequest;
import com.payments.gateway.provider.spi.MandateRequests.MandateQuery;
import com.payments.gateway.provider.spi.ProviderRequests.AcceptDisputeRequest;
import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.CloseCollectionRequest;
import com.payments.gateway.provider.spi.ProviderRequests.ContestDisputeRequest;
import com.payments.gateway.provider.spi.ProviderRequests.CreditsQuery;
import com.payments.gateway.provider.spi.ProviderRequests.DisputeEvidenceUpload;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.SettlementReportQuery;
import com.payments.gateway.provider.spi.ProviderRequests.VoidRequest;
import java.time.Duration;
import java.util.List;

/**
 * Adapter contract for a PSP. Adapters normalize PSP responses and classify failures:
 * {@link ProviderUnavailableException} = definitely not processed (failover allowed),
 * {@link ProviderTimeoutException} = outcome unknown (never fail over). See ADR-005.
 * Every call is made on behalf of one merchant's PSP account (ADR-014).
 */
public interface PaymentProvider {

    String code();

    ProviderCapabilities capabilities();

    /** Credentials the admin API accepts for a merchant's account at this PSP. */
    default List<CredentialField> credentialFields() {
        return List.of();
    }

    ProviderPaymentResult initiatePayment(MerchantAccount account, InitiatePaymentRequest request);

    /** Looks up by provider reference, or by our attempt id when the reference is unknown; NOT_FOUND if never seen. */
    ProviderPaymentResult fetchPaymentStatus(MerchantAccount account, PaymentStatusQuery query);

    ProviderPaymentResult capture(MerchantAccount account, CaptureRequest request);

    ProviderPaymentResult voidAuthorization(MerchantAccount account, VoidRequest request);

    ProviderRefundResult refund(MerchantAccount account, RefundRequest request);

    ProviderRefundResult fetchRefundStatus(MerchantAccount account, RefundStatusQuery query);

    /**
     * Verifies authenticity and normalizes the payload; throws {@link WebhookVerificationException} if invalid.
     * {@code account} is null for the provider-wide endpoint, which only platform-level secrets can authenticate.
     */
    List<ProviderEvent> parseWebhook(MerchantAccount account, InboundWebhook webhook);

    /** Only called when {@link ProviderCapabilities#settlementReports()} is true. */
    default SettlementReport fetchSettlementReport(MerchantAccount account, SettlementReportQuery query) {
        throw new UnsupportedOperationException(code() + " does not provide settlement reports");
    }

    /**
     * How long after a capture or refund succeeds the PSP may take to settle it. A PSP that reports by settlement date
     * settles a day's transactions in later reports, so reconciliation flags an item as missing at the PSP only once
     * this much time has passed (ADR-032).
     */
    default Duration settlementLag() {
        return Duration.ZERO;
    }

    // Mandate operations (ADR-035): only called for instruments in capabilities().mandates().

    default ProviderMandateResult createMandate(MerchantAccount account, CreateMandateRequest request) {
        throw new UnsupportedOperationException(code() + " does not support mandates");
    }

    default ProviderMandateResult fetchMandate(MerchantAccount account, MandateQuery query) {
        throw new UnsupportedOperationException(code() + " does not support mandates");
    }

    /** Cancels the registration, or the mandate once authorized. {@code PENDING} means the PSP is still cancelling. */
    default ProviderMandateResult revokeMandate(MerchantAccount account, MandateQuery query) {
        throw new UnsupportedOperationException(code() + " does not support mandates");
    }

    default ProviderNotificationResult notifyDebit(MerchantAccount account, DebitNotificationRequest request) {
        throw new UnsupportedOperationException(code() + " does not support mandates");
    }

    default ProviderNotificationResult fetchDebitNotification(MerchantAccount account, DebitNotificationQuery query) {
        throw new UnsupportedOperationException(code() + " does not support mandates");
    }

    /** Debits an active mandate; the outcome usually arrives later (webhook or {@link #fetchPaymentStatus}). */
    default ProviderPaymentResult executeDebit(MerchantAccount account, ExecuteDebitRequest request) {
        throw new UnsupportedOperationException(code() + " does not support mandates");
    }

    // Virtual accounts (ADR-038): required of PSPs that declare BANK_TRANSFER.

    /** Every credit the virtual account has received so far. */
    default List<ProviderCredit> fetchCredits(MerchantAccount account, CreditsQuery query) {
        throw new UnsupportedOperationException(code() + " does not support bank transfers");
    }

    /** Stops the account from taking further credits; closing an account already closed succeeds. */
    default void closeCollection(MerchantAccount account, CloseCollectionRequest request) {
        throw new UnsupportedOperationException(code() + " does not support bank transfers");
    }

    // Dispute responses (ADR-039): only called when capabilities().disputeResponses() is true.

    /** Sends one evidence file to the PSP; returns its document id. A permanent refusal is a {@link ProviderRefusedException}. */
    default String uploadDisputeEvidence(MerchantAccount account, DisputeEvidenceUpload upload) {
        throw new UnsupportedOperationException(code() + " does not take dispute responses");
    }

    /** Submits the contest. A refusal because the dispute is no longer open reports the dispute's current status. */
    default ProviderDisputeResponse contestDispute(MerchantAccount account, ContestDisputeRequest request) {
        throw new UnsupportedOperationException(code() + " does not take dispute responses");
    }

    default ProviderDisputeResponse acceptDispute(MerchantAccount account, AcceptDisputeRequest request) {
        throw new UnsupportedOperationException(code() + " does not take dispute responses");
    }
}
