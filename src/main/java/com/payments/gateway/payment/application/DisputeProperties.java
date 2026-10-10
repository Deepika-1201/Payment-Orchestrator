package com.payments.gateway.payment.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** Dispute responses and evidence (LLD §22). */
@ConfigurationProperties("pg.disputes")
public record DisputeProperties(DataSize evidenceMaxFileSize, Integer evidenceMaxFiles, Duration evidenceDueNotice,
                                Duration responseRetry, Duration responseRetryMax, Duration responseLease,
                                Integer noticeBatch) {

    public DisputeProperties {
        evidenceMaxFileSize = evidenceMaxFileSize == null ? DataSize.ofMegabytes(5) : evidenceMaxFileSize;
        evidenceMaxFiles = evidenceMaxFiles == null ? 10 : evidenceMaxFiles;
        evidenceDueNotice = evidenceDueNotice == null ? Duration.ofDays(3) : evidenceDueNotice;
        responseRetry = responseRetry == null ? Duration.ofMinutes(1) : responseRetry;
        responseRetryMax = responseRetryMax == null ? Duration.ofMinutes(30) : responseRetryMax;
        responseLease = responseLease == null ? Duration.ofMinutes(2) : responseLease;
        noticeBatch = noticeBatch == null ? 100 : noticeBatch;
    }
}
