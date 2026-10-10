package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.Dispute;
import com.payments.gateway.payment.domain.DisputeStatus;
import com.payments.gateway.payment.domain.EvidenceFile;
import com.payments.gateway.payment.domain.MerchantResponse;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.TransitionSource;
import com.payments.gateway.payment.infrastructure.DisputeRepository;
import com.payments.gateway.payment.infrastructure.EvidenceFileRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.provider.ProviderClient;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.ProviderDisputeResponse;
import com.payments.gateway.provider.spi.ProviderDisputeResult;
import com.payments.gateway.provider.spi.ProviderRefusedException;
import com.payments.gateway.provider.spi.ProviderRequests.AcceptDisputeRequest;
import com.payments.gateway.provider.spi.ProviderRequests.ContestDisputeRequest;
import com.payments.gateway.provider.spi.ProviderRequests.DisputeEvidenceUpload;
import com.payments.gateway.provider.spi.ProviderRequests.EvidenceDocument;
import com.payments.gateway.provider.spi.ProviderTimeoutException;
import com.payments.gateway.provider.spi.ProviderUnavailableException;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.crypto.BlobCipher;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.EvidenceCategory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Merchant responses to disputes (ADR-039, LLD §22): evidence files kept encrypted, and a contest or an acceptance
 * recorded under the payment's lock, delivered to the PSP after commit and retried until the PSP's deadline.
 */
@Service
public class DisputeResponseService {

    private static final Logger log = LoggerFactory.getLogger(DisputeResponseService.class);
    /** Each accepted type's leading bytes (PDF, JPEG, PNG); the request admits no other type. */
    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "application/pdf", new byte[] {'%', 'P', 'D', 'F', '-'},
            "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    private static final int CLAIM_BATCH = 20;

    public record Upload(EvidenceCategory category, String fileName, String contentType, byte[] content) {
    }

    /** The dispute after a response, and whether the PSP took the response at once. */
    public record Responded(Dispute dispute, boolean sent) {
    }

    private final PaymentRepository payments;
    private final DisputeRepository disputes;
    private final EvidenceFileRepository evidence;
    private final PaymentStore store;
    private final ProviderRegistry providers;
    private final ProviderClient providerClient;
    private final BlobCipher cipher;
    private final DisputeProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public DisputeResponseService(PaymentRepository payments, DisputeRepository disputes, EvidenceFileRepository evidence,
                                  PaymentStore store, ProviderRegistry providers, ProviderClient providerClient,
                                  BlobCipher cipher, DisputeProperties properties, TransactionTemplate tx, Clock clock,
                                  MeterRegistry meters) {
        this.payments = payments;
        this.disputes = disputes;
        this.evidence = evidence;
        this.store = store;
        this.providers = providers;
        this.providerClient = providerClient;
        this.cipher = cipher;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
        for (MerchantResponse.Type type : MerchantResponse.Type.values()) {
            for (String outcome : List.of("sent", "failed")) {
                meters.counter("pg.disputes.responses", "type", wire(type), "outcome", outcome);
            }
        }
    }

    public EvidenceFile upload(String merchantId, String disputeId, Upload upload) {
        Dispute found = respondable(merchantId, disputeId);
        validate(upload);
        Instant now = clock.instant();
        String fileId = Ids.newId("dsf");
        BlobCipher.Sealed sealed = cipher.seal(upload.content(), EvidenceFileRepository.context(fileId));
        EvidenceFile file = new EvidenceFile(fileId, disputeId, merchantId, upload.category(), upload.fileName(),
                upload.contentType(), upload.content().length, HexFormat.of().formatHex(Hashing.sha256(upload.content())),
                null, now);
        tx.executeWithoutResult(status -> {
            payments.lockById(found.paymentId()).orElseThrow();
            disputes.findById(disputeId).orElseThrow().requireOpenForResponse(now);
            if (evidence.countForDispute(disputeId) >= properties.evidenceMaxFiles()) {
                throw GatewayException.validation("evidence_files",
                        "a dispute takes at most " + properties.evidenceMaxFiles() + " files");
            }
            evidence.insert(file, sealed.content(), sealed.wrappedKey());
        });
        return file;
    }

    public List<EvidenceFile> listFiles(String merchantId, String disputeId) {
        return evidence.findByDispute(get(merchantId, disputeId).id());
    }

    public Responded contest(String merchantId, String disputeId, String statement, List<String> fileIds) {
        if (new HashSet<>(fileIds).size() != fileIds.size()) {
            throw GatewayException.validation("evidence_file_ids", "must not repeat a file");
        }
        return respond(merchantId, disputeId, MerchantResponse.Type.CONTEST, statement, fileIds);
    }

    public Responded accept(String merchantId, String disputeId) {
        return respond(merchantId, disputeId, MerchantResponse.Type.ACCEPT, null, null);
    }

    /** Delivers responses whose retry is due; run by the worker (LLD §22.3). */
    public int deliverDue() {
        Instant now = clock.instant();
        List<String> due = disputes.claimDueResponses(now, now.plus(properties.responseLease()), CLAIM_BATCH);
        for (String disputeId : due) {
            try {
                deliver(disputeId);
            } catch (RuntimeException e) {
                log.error("Delivering the response to dispute {} failed; retried after the lease", disputeId, e);
            }
        }
        return due.size();
    }

    private Responded respond(String merchantId, String disputeId, MerchantResponse.Type type, String statement,
                              List<String> fileIds) {
        Dispute found = respondable(merchantId, disputeId);
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            Payment payment = payments.lockById(found.paymentId()).orElseThrow();
            Dispute dispute = disputes.findById(disputeId).orElseThrow();
            dispute.requireOpenForResponse(now);
            if (fileIds != null) {
                Set<String> uploaded = new HashSet<>(evidence.findByDispute(disputeId).stream().map(EvidenceFile::id).toList());
                List<String> unknown = fileIds.stream().filter(id -> !uploaded.contains(id)).toList();
                if (!unknown.isEmpty()) {
                    throw GatewayException.validation("evidence_file_ids", "not evidence files of this dispute: " + unknown);
                }
            }
            dispute.requestResponse(type, statement, fileIds, now.plus(properties.responseLease()), now);
            store.saveDispute(payment, dispute);
        });
        deliver(disputeId);
        Dispute after = disputes.findById(disputeId).orElseThrow();
        return new Responded(after, after.response().status() == MerchantResponse.Status.SENT);
    }

    private void deliver(String disputeId) {
        Dispute dispute = disputes.findById(disputeId).orElseThrow();
        MerchantResponse response = dispute.response();
        if (response == null || response.status() != MerchantResponse.Status.PENDING) {
            return;
        }
        ProviderDisputeResponse answer;
        try {
            answer = response.type() == MerchantResponse.Type.CONTEST ? contest(dispute, response)
                    : providerClient.acceptDispute(dispute.merchantId(), dispute.providerCode(),
                            new AcceptDisputeRequest(dispute.providerDisputeId()));
        } catch (ProviderRefusedException e) {
            answer = ProviderDisputeResponse.refused(e.failureCode(), e.getMessage());
        } catch (ProviderTimeoutException | ProviderUnavailableException e) {
            log.warn("Response to dispute {} not delivered yet: {}", disputeId, e.getMessage());
            retryOrFail(disputeId);
            return;
        }
        apply(disputeId, answer);
    }

    /** Uploads the files not yet at the PSP (keeping their document ids, so a retry never sends one twice), then submits. */
    private ProviderDisputeResponse contest(Dispute dispute, MerchantResponse response) {
        List<EvidenceDocument> documents = new ArrayList<>();
        for (EvidenceFileRepository.Stored stored : evidence.findWithContent(dispute.id(), response.fileIds())) {
            EvidenceFile file = stored.file();
            String documentId = file.providerDocumentId();
            if (documentId == null) {
                byte[] content = cipher.open(stored.contentEnc(), stored.contentKeyEnc(),
                        EvidenceFileRepository.context(file.id()));
                documentId = providerClient.uploadDisputeEvidence(dispute.merchantId(), dispute.providerCode(),
                        new DisputeEvidenceUpload(dispute.providerDisputeId(), file.id(), file.fileName(),
                                file.contentType(), content));
                evidence.setProviderDocumentId(file.id(), documentId);
            }
            documents.add(new EvidenceDocument(file.category(), documentId));
        }
        return providerClient.contestDispute(dispute.merchantId(), dispute.providerCode(),
                new ContestDisputeRequest(dispute.providerDisputeId(), response.statement(), documents));
    }

    private void apply(String disputeId, ProviderDisputeResponse answer) {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            Payment payment = payments.lockById(disputes.findById(disputeId).orElseThrow().paymentId()).orElseThrow();
            Dispute dispute = disputes.findById(disputeId).orElseThrow();
            MerchantResponse response = dispute.response();
            if (response.status() != MerchantResponse.Status.PENDING) {
                return;
            }
            ProviderDisputeResult reported = answer.dispute();
            boolean sent = answer.outcome() == ProviderDisputeResponse.Outcome.ACCEPTED
                    || reported != null && reachedBy(response.type(), reported.status());
            if (sent) {
                dispute.responseSent(now);
            } else {
                dispute.responseFailed(reported != null
                        ? "dispute_already_" + reported.status().name().toLowerCase(Locale.ROOT) : answer.failureCode(), now);
                log.error("{} refused the response to dispute {}: {} {}", dispute.providerCode(), disputeId,
                        answer.failureCode(), answer.failureMessage());
            }
            if (reported != null) {
                dispute.apply(DisputeStatus.valueOf(reported.status().name()), reported.respondBy(),
                        TransitionSource.PROVIDER_RESPONSE, now);
            }
            store.saveDispute(payment, dispute);
            meters.counter("pg.disputes.responses", "type", wire(response.type()), "outcome", sent ? "sent" : "failed")
                    .increment();
        });
    }

    /** Not delivered this time: try again later, unless the PSP's deadline has passed. */
    private void retryOrFail(String disputeId) {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            Payment payment = payments.lockById(disputes.findById(disputeId).orElseThrow().paymentId()).orElseThrow();
            Dispute dispute = disputes.findById(disputeId).orElseThrow();
            MerchantResponse response = dispute.response();
            if (response.status() != MerchantResponse.Status.PENDING) {
                return;
            }
            if (deadlinePassed(dispute, now)) {
                dispute.responseFailed("deadline_passed", now);
                log.error("Response to dispute {} was not delivered before its deadline {}", disputeId, dispute.respondBy());
                meters.counter("pg.disputes.responses", "type", wire(response.type()), "outcome", "failed").increment();
            } else {
                dispute.retryResponseAt(now.plus(backoff(response.attempts())), now);
            }
            store.saveDispute(payment, dispute);
        });
    }

    private Duration backoff(int attempts) {
        Duration delay = properties.responseRetry().multipliedBy(1L << Math.min(attempts, 16));
        return delay.compareTo(properties.responseRetryMax()) > 0 ? properties.responseRetryMax() : delay;
    }

    /** What the response leads to at the PSP: review for a contest, a loss for an acceptance. */
    private static boolean reachedBy(MerchantResponse.Type type, ProviderDisputeResult.Status status) {
        return status == (type == MerchantResponse.Type.CONTEST ? ProviderDisputeResult.Status.UNDER_REVIEW
                : ProviderDisputeResult.Status.LOST);
    }

    private static boolean deadlinePassed(Dispute dispute, Instant now) {
        return dispute.respondBy() != null && !now.isBefore(dispute.respondBy());
    }

    private Dispute respondable(String merchantId, String disputeId) {
        Dispute dispute = get(merchantId, disputeId);
        if (!providers.require(dispute.providerCode()).capabilities().disputeResponses()) {
            throw new GatewayException(ErrorCode.DISPUTE_RESPONSE_UNSUPPORTED, "Provider " + dispute.providerCode()
                    + " takes dispute responses only on its own dashboard");
        }
        return dispute;
    }

    private Dispute get(String merchantId, String disputeId) {
        return disputes.findForMerchant(merchantId, disputeId)
                .orElseThrow(() -> GatewayException.notFound("Dispute", disputeId));
    }

    private void validate(Upload upload) {
        byte[] signature = SIGNATURES.get(upload.contentType());
        byte[] content = upload.content();
        if (content.length > properties.evidenceMaxFileSize().toBytes()) {
            throw GatewayException.validation("content_base64",
                    "the file must not exceed " + properties.evidenceMaxFileSize().toBytes() + " bytes");
        }
        if (content.length < signature.length
                || !Arrays.equals(content, 0, signature.length, signature, 0, signature.length)) {
            throw GatewayException.validation("content_base64", "is not a " + upload.contentType() + " file");
        }
    }

    private static String wire(MerchantResponse.Type type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
