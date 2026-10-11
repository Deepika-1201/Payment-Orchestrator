package com.payments.gateway.payout;

import java.time.Instant;

public record PayoutRecord(String id, String merchantId, String batchId, String kycId, String providerCode, long amount,
                           byte[] instructionEncrypted, byte[] instructionHash, PayoutState.Status status,
                           Instant dispatchedAt, String providerReference, String bankReference, String reason,
                           int checks, Instant nextCheckAt, Instant createdAt, Instant updatedAt) {
}