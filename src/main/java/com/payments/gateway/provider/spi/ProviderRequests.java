package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.EvidenceCategory;
import com.payments.gateway.shared.model.Money;
import java.util.List;

public final class ProviderRequests {

    private ProviderRequests() {
    }

    public record PaymentStatusQuery(String attemptId, String providerReference) {
    }

    public record CaptureRequest(String attemptId, String providerReference, Money amount) {
    }

    public record VoidRequest(String attemptId, String providerReference) {
    }

    /** {@code refundId} is sent to the PSP as the refund idempotency key. */
    public record RefundRequest(String refundId, String attemptId, String paymentProviderReference, Money amount,
                                String reason) {
    }

    /**
     * {@code providerRefundReference} is null when the refund call timed out; PSPs that list refunds per payment find it
     * by our {@code refundId} under {@code paymentProviderReference}, PSPs that key refunds by order under
     * {@code attemptId}.
     */
    public record RefundStatusQuery(String refundId, String providerRefundReference, String paymentProviderReference,
                                    String attemptId) {
    }

    /** {@code merchantId} selects the merchant's PSP account (orchestrator mode); window is [from, to). */
    public record SettlementReportQuery(String merchantId, java.time.Instant from, java.time.Instant to) {
    }

    /** A virtual account (ADR-038): {@code collectionReference} is the PSP's id, the attempt's provider reference. */
    public record CreditsQuery(String attemptId, String collectionReference) {
    }

    public record CloseCollectionRequest(String attemptId, String collectionReference) {
    }

    /** One evidence file for a dispute (ADR-039); {@code disputeReference} is the PSP's dispute id. */
    public record DisputeEvidenceUpload(String disputeReference, String fileId, String fileName, String contentType,
                                        byte[] content) {
    }

    /** A document already at the PSP, as evidence of {@code category}. */
    public record EvidenceDocument(EvidenceCategory category, String documentId) {
    }

    public record ContestDisputeRequest(String disputeReference, String statement, List<EvidenceDocument> documents) {

        public ContestDisputeRequest {
            documents = List.copyOf(documents);
        }
    }

    public record AcceptDisputeRequest(String disputeReference) {
    }
}
