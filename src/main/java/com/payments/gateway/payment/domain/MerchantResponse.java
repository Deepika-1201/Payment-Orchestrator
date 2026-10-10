package com.payments.gateway.payment.domain;

import java.time.Instant;
import java.util.List;

/**
 * The merchant's response to a dispute (ADR-039): a contest with a statement and evidence files, or an acceptance. It
 * is recorded as {@code PENDING} and then delivered; {@code FAILED} when the PSP refused it or the deadline passed first.
 */
public record MerchantResponse(Type type, Status status, String statement, List<String> fileIds, Instant requestedAt,
                               Instant sentAt, String failure, int attempts, Instant nextAttemptAt) {

    public enum Type {
        CONTEST,
        ACCEPT
    }

    public enum Status {
        PENDING,
        SENT,
        FAILED
    }

    public MerchantResponse {
        fileIds = fileIds == null ? null : List.copyOf(fileIds);
    }

    static MerchantResponse pending(Type type, String statement, List<String> fileIds, Instant now, Instant firstAttemptAt) {
        return new MerchantResponse(type, Status.PENDING, statement, fileIds, now, null, null, 0, firstAttemptAt);
    }

    MerchantResponse sent(Instant now) {
        return new MerchantResponse(type, Status.SENT, statement, fileIds, requestedAt, now, null, attempts + 1, null);
    }

    MerchantResponse failed(String reason) {
        return new MerchantResponse(type, Status.FAILED, statement, fileIds, requestedAt, null, reason, attempts + 1, null);
    }

    MerchantResponse retryAt(Instant next) {
        return new MerchantResponse(type, Status.PENDING, statement, fileIds, requestedAt, null, null, attempts + 1, next);
    }
}
