package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.EvidenceCategory;
import java.time.Instant;

/**
 * A file the merchant uploaded as dispute evidence (ADR-039). Only its description lives here: the content and its key
 * stay encrypted in storage. {@code providerDocumentId} is set once the file has been uploaded to the PSP.
 */
public record EvidenceFile(String id, String disputeId, String merchantId, EvidenceCategory category, String fileName,
                           String contentType, int size, String sha256, String providerDocumentId, Instant createdAt) {
}
