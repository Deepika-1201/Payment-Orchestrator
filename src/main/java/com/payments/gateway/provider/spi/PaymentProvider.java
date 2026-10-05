package com.payments.gateway.provider.spi;

import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
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
}
