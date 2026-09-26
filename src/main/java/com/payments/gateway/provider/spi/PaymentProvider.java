package com.payments.gateway.provider.spi;

import com.payments.gateway.provider.spi.ProviderRequests.CaptureRequest;
import com.payments.gateway.provider.spi.ProviderRequests.PaymentStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.RefundRequest;
import com.payments.gateway.provider.spi.ProviderRequests.RefundStatusQuery;
import com.payments.gateway.provider.spi.ProviderRequests.VoidRequest;
import java.util.List;

/**
 * Adapter contract for a PSP. Adapters normalize PSP responses and classify failures:
 * {@link ProviderUnavailableException} = definitely not processed (failover allowed),
 * {@link ProviderTimeoutException} = outcome unknown (never fail over). See ADR-005.
 */
public interface PaymentProvider {

    String code();

    ProviderCapabilities capabilities();

    ProviderPaymentResult initiatePayment(InitiatePaymentRequest request);

    /** Looks up by provider reference, or by our attempt id when the reference is unknown; NOT_FOUND if never seen. */
    ProviderPaymentResult fetchPaymentStatus(PaymentStatusQuery query);

    ProviderPaymentResult capture(CaptureRequest request);

    ProviderPaymentResult voidAuthorization(VoidRequest request);

    ProviderRefundResult refund(RefundRequest request);

    ProviderRefundResult fetchRefundStatus(RefundStatusQuery query);

    /** Verifies authenticity and normalizes the payload; throws {@link WebhookVerificationException} if invalid. */
    List<ProviderEvent> parseWebhook(InboundWebhook webhook);
}
