package com.payments.gateway.payment;

import com.payments.gateway.merchant.MerchantRepository;
import com.payments.gateway.merchant.SecretRotationService;
import com.payments.gateway.payment.application.DisputeDeadlineJob;
import com.payments.gateway.payment.application.DisputeProperties;
import com.payments.gateway.payment.application.DisputeResponseService;
import com.payments.gateway.payment.application.PaymentStore;
import com.payments.gateway.payment.infrastructure.DisputeRepository;
import com.payments.gateway.payment.infrastructure.EvidenceFileRepository;
import com.payments.gateway.payment.infrastructure.PaymentRepository;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.crypto.BlobCipher;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.support.FakeMerchantEndpoint;
import com.payments.gateway.support.IntegrationTest;
import com.payments.gateway.support.OpenApiContract;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

import static com.payments.gateway.support.JsonPath.list;
import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Dispute responses through the API (FR-D2, FR-D3, ADR-039, LLD §22). */
class DisputeEvidenceIntegrationTest extends IntegrationTest {

    private static final byte[] PDF = "%PDF-1.7\n%evidence\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] TEST_KEY = Base64.getDecoder().decode("bG9jYWwtZGV2LWtleS0wMDAwMDAwMDAwMDAwMDAwMDA=");

    @Autowired
    private DisputeResponseService responses;
    @Autowired
    private DisputeDeadlineJob deadlines;
    @Autowired
    private EvidenceFileRepository evidenceFiles;
    @Autowired
    private MerchantRepository merchants;
    @Autowired
    private AuditLogger audit;
    @Autowired
    private SecretCipher secretCipher;
    @Autowired
    private MeterRegistry meters;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private DisputeRepository disputes;
    @Autowired
    private PaymentStore store;
    @Autowired
    private TransactionTemplate tx;

    private record Disputed(TestMerchant merchant, String paymentId, String disputeId, String pspDisputeId,
                            String provider) {
    }

    @Test
    void aContestWithEvidenceReachesThePspAndPutsTheDisputeUnderReview() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            Disputed disputed = open(createMerchantWith(endpoint.url(), null, ALPHA), 100_000, 100_000);
            String receipt = uploaded(disputed, "shipping_proof", "courier-receipt.pdf", "application/pdf", PDF);
            String chat = uploaded(disputed, "customer_communication", "chat.png", "image/png", PNG);
            List<Map<String, Object>> files = list(assertContract("GET", "/v1/disputes/{dispute_id}/evidence_files", null,
                    get(disputed.merchant(), "/v1/disputes/" + disputed.disputeId() + "/evidence_files")).body(), "data");
            assertThat(files).extracting(file -> file.get("id")).containsExactly(receipt, chat);

            Map<String, Object> request = Map.of("statement", "Delivered on 2 October; receipt attached.",
                    "evidence_file_ids", List.of(receipt, chat));
            Response contested = assertContract("POST", "/v1/disputes/{dispute_id}/contest", request,
                    post(disputed.merchant(), "/v1/disputes/" + disputed.disputeId() + "/contest", key(), request));

            assertThat(contested.status()).as(contested.raw()).isEqualTo(200);
            assertThat(str(contested.body(), "status")).isEqualTo("under_review");
            assertThat(str(contested.body(), "response.type")).isEqualTo("contest");
            assertThat(str(contested.body(), "response.status")).isEqualTo("sent");
            Map<String, Object> atPsp = mockView(disputed);
            assertThat(list(atPsp, "documents")).extracting(document -> document.get("sha256"))
                    .containsExactly(sha256(PDF), sha256(PNG));
            assertThat(str(atPsp, "contest.statement")).isEqualTo("Delivered on 2 October; receipt attached.");
            List<Map<String, Object>> documents = list(atPsp, "documents");
            assertThat(atPsp).extracting("contest").extracting("evidence").isEqualTo(Map.of(
                    "shipping_proof", List.of(documents.get(0).get("id")),
                    "customer_communication", List.of(documents.get(1).get("id"))));
            assertThat(ledgerBalances(disputed.merchant())).as("nothing moves until the PSP decides")
                    .containsEntry("chargebacks", 100_000L);
            assertThat(contest(disputed, List.of(receipt)).status()).isEqualTo(409);
            Response late = upload(disputed, "other", "more.pdf", "application/pdf", PDF);
            assertThat(late.status()).isEqualTo(409);
            assertThat(str(late.body(), "code")).isEqualTo("dispute_invalid_state");
            assertThat(eventTypes(endpoint)).containsExactlyInAnyOrder("dispute.created", "dispute.updated");
        }
    }

    @Test
    void acceptingADisputeBooksTheLoss() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            Disputed disputed = open(createMerchantWith(endpoint.url(), null, ALPHA), 50_000, 50_000);

            Response accepted = assertContract("POST", "/v1/disputes/{dispute_id}/accept", null,
                    post(disputed.merchant(), "/v1/disputes/" + disputed.disputeId() + "/accept", key(), null));

            assertThat(accepted.status()).as(accepted.raw()).isEqualTo(200);
            assertThat(str(accepted.body(), "status")).isEqualTo("lost");
            assertThat(str(accepted.body(), "response.type")).isEqualTo("accept");
            assertThat(accepted.body()).extracting("response").asInstanceOf(InstanceOfAssertFactories.MAP)
                    .doesNotContainKeys("statement", "evidence_file_ids");
            assertThat(mockView(disputed)).containsEntry("accepted", true).containsEntry("status", "lost");
            assertThat(ledgerBalances(disputed.merchant())).containsEntry("chargebacks", 50_000L)
                    .containsEntry("psp_receivable", 0L);
            assertThat(count("SELECT count(*) FROM ledger_transactions WHERE type = 'REVERSAL'")).isZero();
            assertThat(post(disputed.merchant(), "/v1/payments/" + disputed.paymentId() + "/refunds", key(),
                    Map.of("amount", 1_000)).status()).as("the lost amount stays out of the refundable amount").isEqualTo(422);
            assertThat(accept(disputed).status()).isEqualTo(409);
            assertThat(eventTypes(endpoint)).containsExactlyInAnyOrder("dispute.created", "dispute.lost");
        }
    }

    @Test
    void aContestThatTimedOutAfterThePspTookItIsSettledByTheRetryWithoutUploadingAgain() {
        Disputed disputed = open(createMerchant(ALPHA), 200_000, 100_001);
        String receipt = uploaded(disputed, "billing_proof", "invoice.pdf", "application/pdf", PDF);

        Response contested = contest(disputed, List.of(receipt));
        assertThat(contested.status()).as(contested.raw()).isEqualTo(202);
        assertThat(str(contested.body(), "response.status")).isEqualTo("pending");
        assertThat(responses.deliverDue()).as("the retry waits for its backoff").isZero();

        clock.advance(Duration.ofMinutes(3));
        assertThat(responses.deliverDue()).isEqualTo(1);

        Map<String, Object> dispute = getDispute(disputed);
        assertThat(str(dispute, "response.status")).isEqualTo("sent");
        assertThat(str(dispute, "status")).isEqualTo("under_review");
        assertThat(list(mockView(disputed), "documents")).hasSize(1);
    }

    @Test
    void aContestThePspNeverReceivedIsSentAgainWithTheDocumentsAlreadyThere() {
        Disputed disputed = open(createMerchant(ALPHA), 200_000, 100_005);
        String receipt = uploaded(disputed, "billing_proof", "invoice.jpg", "image/jpeg", JPEG);

        assertThat(contest(disputed, List.of(receipt)).status()).isEqualTo(202);
        clock.advance(Duration.ofMinutes(3));
        responses.deliverDue();

        assertThat(str(getDispute(disputed), "response.status")).isEqualTo("sent");
        Map<String, Object> atPsp = mockView(disputed);
        assertThat(list(atPsp, "documents")).hasSize(1);
        assertThat(str(atPsp, "status")).isEqualTo("under_review");
        assertThat(count("SELECT count(*) FROM disputes WHERE response_attempts = 2")).isEqualTo(1);
    }

    @Test
    void aRefusedContestFailsGoesToReviewAndCanBeReplacedByAnAcceptance() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            Disputed disputed = open(createMerchantWith(endpoint.url(), null, ALPHA), 200_000, 100_003);
            String receipt = uploaded(disputed, "other", "note.pdf", "application/pdf", PDF);

            Response refused = contest(disputed, List.of(receipt));

            assertThat(refused.status()).isEqualTo(202);
            assertThat(str(refused.body(), "response.status")).isEqualTo("failed");
            assertThat(str(refused.body(), "response.failure_reason")).isEqualTo("evidence_rejected");
            assertThat(str(refused.body(), "status")).isEqualTo("open");
            Map<String, Object> review = list(admin("GET", "/admin/v1/reviews?kind=dispute", null).body(), "data").getFirst();
            assertThat(review.get("reasons")).isEqualTo(List.of("response_failed"));
            assertThat(eventTypes(endpoint)).contains("dispute.response_failed");

            Response accepted = accept(disputed);
            assertThat(accepted.status()).as(accepted.raw()).isEqualTo(200);
            assertThat(str(accepted.body(), "status")).isEqualTo("lost");
        }
    }

    @Test
    void aResponseStillUndeliveredAtTheDeadlineFails() {
        Disputed disputed = open(createMerchant(ALPHA), 100_000, 100_000);
        alpha().psp().setAvailable(false);

        Response pending = accept(disputed);
        assertThat(pending.status()).isEqualTo(202);
        assertThat(str(pending.body(), "response.status")).isEqualTo("pending");
        assertThat(nextAttemptIn()).as("first retry").isEqualTo(Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(1));
        responses.deliverDue();
        assertThat(nextAttemptIn()).as("doubling").isEqualTo(Duration.ofMinutes(2));
        for (int retry = 0; retry < 5; retry++) {
            clock.advance(nextAttemptIn());
            responses.deliverDue();
        }
        assertThat(nextAttemptIn()).as("capped").isEqualTo(Duration.ofMinutes(30));
        clock.advance(Duration.ofDays(3));
        responses.deliverDue();
        assertThat(str(getDispute(disputed), "response.status")).as("retried until the deadline").isEqualTo("pending");

        clock.advance(Duration.ofDays(5));
        responses.deliverDue();

        Map<String, Object> dispute = getDispute(disputed);
        assertThat(str(dispute, "response.status")).isEqualTo("failed");
        assertThat(str(dispute, "response.failure_reason")).isEqualTo("deadline_passed");
        assertThat(count("SELECT count(*) FROM disputes WHERE needs_review AND review_reason = 'response_failed'")).isEqualTo(1);
        alpha().psp().setAvailable(true);
        Response tooLate = accept(disputed);
        assertThat(tooLate.status()).isEqualTo(409);
        assertThat(str(tooLate.body(), "detail")).contains("deadline");
    }

    @Test
    void aResponseToADisputeThePspHasMovedOnFromFailsAndBringsItsStatusUpToDate() {
        Disputed disputed = open(createMerchant(ALPHA), 100_000, 100_000);
        String receipt = uploaded(disputed, "other", "a.pdf", "application/pdf", PDF);
        Response moved = send("POST", "/simulator/" + ALPHA + "/disputes/" + disputed.pspDisputeId() + "/status", Map.of(),
                Map.of("status", "under_review", "send_webhook", false));
        assertThat(moved.status()).isEqualTo(200);

        Response accepted = accept(disputed);

        assertThat(accepted.status()).isEqualTo(202);
        assertThat(str(accepted.body(), "response.status")).isEqualTo("failed");
        assertThat(str(accepted.body(), "response.failure_reason")).isEqualTo("dispute_already_under_review");
        assertThat(str(accepted.body(), "status")).as("brought up to date").isEqualTo("under_review");
        assertThat(contest(disputed, List.of(receipt)).status()).as("no longer open").isEqualTo(409);
    }

    @Test
    void aDisputeKnownOnlyFromASettlementReportHasNoDeadlineAndCanStillBeAnswered() {
        TestMerchant merchant = createMerchant(ALPHA);
        Map<String, Object> payment = payAndSucceed(merchant, 100_000);
        assertThat(send("POST", "/simulator/" + ALPHA + "/payments/" + str(payment, "latest_attempt.provider_reference")
                + "/dispute", Map.of(), Map.of("send_webhook", false)).status()).isEqualTo(200);
        assertThat(admin("POST", "/admin/v1/reconciliation/runs", Map.of("merchant_id", merchant.id(), "provider", ALPHA,
                "from", clock.instant().minus(Duration.ofHours(1)).toString(),
                "to", clock.instant().plus(Duration.ofHours(1)).toString())).status()).isEqualTo(201);
        Map<String, Object> dispute = list(get(merchant, "/v1/payments/" + str(payment, "id") + "/disputes").body(), "data")
                .getFirst();
        assertThat(dispute).doesNotContainKey("respond_by");

        Response accepted = post(merchant, "/v1/disputes/" + str(dispute, "id") + "/accept", key(), null);

        assertThat(accepted.status()).as(accepted.raw()).isEqualTo(200);
        assertThat(str(accepted.body(), "status")).isEqualTo("lost");
    }

    @Test
    void aDocumentThePspRejectsFailsTheContest() {
        Disputed disputed = open(createMerchant(ALPHA), 200_000, 100_002);
        String receipt = uploaded(disputed, "billing_proof", "invoice.pdf", "application/pdf", PDF);

        Response contested = contest(disputed, List.of(receipt));

        assertThat(contested.status()).isEqualTo(202);
        assertThat(str(contested.body(), "response.status")).isEqualTo("failed");
        assertThat(str(contested.body(), "response.failure_reason")).isEqualTo("document_rejected");
        assertThat(responses.deliverDue()).as("not retried").isZero();
    }

    @Test
    void pspsThatTakeNoResponsesThroughTheApiRefuseThem() {
        Disputed disputed = open(createMerchant(BETA), 100_000, 100_000);

        Response upload = assertContract("POST", "/v1/disputes/{dispute_id}/evidence_files", null,
                upload(disputed, "other", "note.pdf", "application/pdf", PDF));
        Response accept = accept(disputed);

        assertThat(upload.status()).isEqualTo(422);
        assertThat(str(upload.body(), "code")).isEqualTo("dispute_response_unsupported");
        assertThat(accept.status()).isEqualTo(422);
        assertThat(contest(disputed, List.of("dsf_unknown")).status()).isEqualTo(422);
        assertThat(count("SELECT count(*) FROM disputes WHERE response IS NOT NULL")).isZero();
    }

    @Test
    void evidenceFilesAreCheckedBeforeTheyAreStored() {
        Disputed disputed = open(createMerchant(ALPHA), 100_000, 100_000);

        assertThat(upload(disputed, "other", "x.pdf", "application/pdf", PNG).status()).as("not a PDF").isEqualTo(400);
        assertThat(upload(disputed, "other", "x.png", "image/png", new byte[] {(byte) 0x89, 'P'}).status()).isEqualTo(400);
        assertThat(upload(disputed, "other", "../x.pdf", "application/pdf", PDF).status()).isEqualTo(400);
        assertThat(upload(disputed, "receipts", "x.pdf", "application/pdf", PDF).status()).isEqualTo(400);
        assertThat(upload(disputed, "other", "x.gif", "image/gif", PDF).status()).isEqualTo(400);
        Response notBase64 = post(disputed.merchant(), "/v1/disputes/" + disputed.disputeId() + "/evidence_files", key(),
                Map.of("category", "other", "file_name", "x.pdf", "content_type", "application/pdf",
                        "content_base64", "%%%"));
        assertThat(notBase64.status()).isEqualTo(400);
        byte[] fiveMegabytesAndOne = Arrays.copyOf(PDF, 5 * 1024 * 1024 + 1);
        Response tooBig = upload(disputed, "other", "big.pdf", "application/pdf", fiveMegabytesAndOne);
        assertThat(tooBig.status()).as("within the body limit, over the file limit").isEqualTo(400);
        Response overTheBodyLimit = upload(disputed, "other", "huge.pdf", "application/pdf",
                Arrays.copyOf(PDF, 6 * 1024 * 1024));
        assertThat(overTheBodyLimit.status()).isEqualTo(413);
        String bigStatement = "x".repeat(300 * 1024);
        assertThat(contest(disputed, bigStatement, List.of("dsf_x")).status()).as("other paths keep 256 KB")
                .isEqualTo(413);
        assertThat(count("SELECT count(*) FROM dispute_evidence_files")).isZero();

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(uploaded(disputed, "other", "page" + i + ".pdf", "application/pdf", PDF));
        }
        Response eleventh = upload(disputed, "other", "page10.pdf", "application/pdf", PDF);
        assertThat(eleventh.status()).isEqualTo(400);
        assertThat(str(eleventh.body(), "detail")).contains("at most 10");

        Disputed other = open(disputed.merchant(), 100_000, 100_000);
        assertThat(contest(disputed, List.of(ids.getFirst(), ids.getFirst())).status()).as("repeated").isEqualTo(400);
        assertThat(contest(disputed, List.of(uploaded(other, "other", "o.pdf", "application/pdf", PDF))).status())
                .as("another dispute's file").isEqualTo(400);
        assertThat(contest(disputed, "", List.of(ids.getFirst())).status()).isEqualTo(400);
        assertThat(contest(disputed, "x".repeat(1001), List.of(ids.getFirst())).status()).isEqualTo(400);
        assertThat(contest(disputed, List.of()).status()).isEqualTo(400);
        assertThat(contest(disputed, ids.subList(0, 10)).status()).as("ten files are fine").isEqualTo(200);
    }

    @Test
    void anUploadRetriedWithItsKeyIsStoredOnce() {
        Disputed disputed = open(createMerchant(ALPHA), 100_000, 100_000);
        String key = key();
        Map<String, Object> body = uploadBody("other", "note.pdf", "application/pdf", PDF);

        Response first = post(disputed.merchant(), "/v1/disputes/" + disputed.disputeId() + "/evidence_files", key, body);
        Response again = post(disputed.merchant(), "/v1/disputes/" + disputed.disputeId() + "/evidence_files", key, body);

        assertThat(again.raw()).isEqualTo(first.raw());
        assertThat(count("SELECT count(*) FROM dispute_evidence_files")).isEqualTo(1);
    }

    @Test
    void evidenceIsStoredEncryptedAndItsKeyFollowsADataKeyRotation() {
        Disputed disputed = open(createMerchant(ALPHA), 100_000, 100_000);
        String fileId = uploaded(disputed, "shipping_proof", "receipt.pdf", "application/pdf", PDF);
        byte[] content = jdbc.sql("SELECT content_enc FROM dispute_evidence_files WHERE id = ?").param(1, fileId)
                .query(byte[].class).single();
        byte[] wrapped = jdbc.sql("SELECT content_key_enc FROM dispute_evidence_files WHERE id = ?").param(1, fileId)
                .query(byte[].class).single();

        assertThat(content).hasSize(PDF.length + BlobCipher.OVERHEAD);
        assertThat(new String(content, StandardCharsets.ISO_8859_1)).doesNotContain("%PDF");
        assertThat(SecretCipher.keyId(wrapped)).contains(secretCipher.primaryKeyId());
        assertThat(new BlobCipher(secretCipher).open(content, wrapped, EvidenceFileRepository.context(fileId))).isEqualTo(PDF);
        assertThat(catchThrowable(() -> new BlobCipher(secretCipher).open(content, wrapped,
                EvidenceFileRepository.context("dsf_other")))).as("bound to its file").isNotNull();

        byte[] newKey = new byte[32];
        new java.security.SecureRandom().nextBytes(newKey);
        SecretCipher rotated = new SecretCipher(Map.of(SecretCipher.LEGACY_KEY_ID, TEST_KEY, "k2026-10", newKey), "k2026-10");
        SecretRotationService rotation = new SecretRotationService(merchants, List.of(evidenceFiles), rotated, audit);
        int merchantSecrets = new SecretRotationService(merchants, List.of(), rotated, audit).usage().ciphertextsByKey()
                .get(SecretCipher.LEGACY_KEY_ID);
        assertThat(rotation.usage().ciphertextsByKey()).as("the file key counts")
                .containsEntry(SecretCipher.LEGACY_KEY_ID, merchantSecrets + 1);

        assertThat(rotation.reEncryptAll("admin-token-0").usage().complete()).isTrue();

        byte[] rewrapped = jdbc.sql("SELECT content_key_enc FROM dispute_evidence_files WHERE id = ?").param(1, fileId)
                .query(byte[].class).single();
        SecretCipher newKeyOnly = new SecretCipher(Map.of("k2026-10", newKey), "k2026-10");
        assertThat(new BlobCipher(newKeyOnly).open(content, rewrapped, EvidenceFileRepository.context(fileId)))
                .as("the file itself is untouched").isEqualTo(PDF);
        assertThat(jdbc.sql("SELECT content_enc FROM dispute_evidence_files WHERE id = ?").param(1, fileId)
                .query(byte[].class).single()).isEqualTo(content);
    }

    @Test
    void theEvidenceDueNoticeIsSentOnceAndTheGaugeCountsUnansweredDisputes() {
        try (FakeMerchantEndpoint endpoint = new FakeMerchantEndpoint()) {
            Disputed disputed = open(createMerchantWith(endpoint.url(), null, ALPHA), 100_000, 100_000);
            Disputed answered = open(disputed.merchant(), 100_000, 100_000);
            Disputed decided = open(disputed.merchant(), 100_000, 100_000);
            assertThat(send("POST", "/simulator/" + ALPHA + "/disputes/" + decided.pspDisputeId() + "/status", Map.of(),
                    Map.of("status", "lost")).status()).isEqualTo(200);
            assertThat(deadlines.notifyDue()).isZero();
            assertThat(evidenceDueGauge()).isZero();

            clock.advance(Duration.ofDays(4).plusMinutes(1));
            assertThat(accept(answered).status()).isEqualTo(200);

            assertThat(deadlines.notifyDue()).isEqualTo(1);
            assertThat(deadlines.notifyDue()).as("once per dispute").isZero();
            assertThat(evidenceDueGauge()).isEqualTo(1);
            assertThat(eventTypes(endpoint)).filteredOn("dispute.evidence_due"::equals).hasSize(1);
            List<Map<String, Object>> due = list(admin("GET", "/admin/v1/disputes?evidence_due=true", null).body(), "data");
            assertThat(due).extracting(row -> row.get("merchant_id")).containsExactly(disputed.merchant().id());
            assertThat(str(due.getFirst(), "dispute.id")).isEqualTo(disputed.disputeId());
            assertThat(list(admin("GET", "/admin/v1/disputes?evidence_due=true&merchant_id=" + disputed.merchant().id(), null)
                    .body(), "data")).hasSize(1);
            assertThat(list(admin("GET", "/admin/v1/disputes?evidence_due=true&merchant_id=mer_other", null).body(), "data"))
                    .isEmpty();

            assertThat(contest(disputed, List.of(uploaded(disputed, "other", "a.pdf", "application/pdf", PDF))).status())
                    .isEqualTo(200);
            assertThat(evidenceDueGauge()).isZero();
            assertThat(list(admin("GET", "/admin/v1/disputes?evidence_due=true", null).body(), "data")).isEmpty();
        }
    }

    @Test
    void theNoticeWorksThroughMoreDisputesThanOneBatchPastDecidedOnes() {
        TestMerchant merchant = createMerchant(ALPHA);
        for (String decision : List.of("won", "lost")) {
            Disputed decided = open(merchant, 100_000, 100_000);
            assertThat(send("POST", "/simulator/" + ALPHA + "/disputes/" + decided.pspDisputeId() + "/status", Map.of(),
                    Map.of("status", decision)).status()).isEqualTo(200);
        }
        clock.advance(Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) {
            open(merchant, 100_000, 100_000);
        }
        DisputeDeadlineJob smallBatches = new DisputeDeadlineJob(payments, disputes, store,
                new DisputeProperties(null, null, null, null, null, null, 2), tx, clock, new SimpleMeterRegistry());
        clock.advance(Duration.ofDays(5));

        assertThat(smallBatches.notifyDue()).as("the decided disputes, due first, never fill a batch").isEqualTo(3);
        assertThat(count("SELECT count(*) FROM disputes WHERE evidence_due_notified_at IS NOT NULL")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM disputes WHERE status IN ('WON', 'LOST')")).isEqualTo(2);
    }

    @Test
    void aDisputeAnsweredWhileThePspStillShowsItOpenIsNotDue() {
        Disputed answered = open(createMerchant(ALPHA), 100_000, 100_000);
        // A PSP may take an answer and move the dispute on later; delivery then records it as sent on an open dispute.
        jdbc.sql("""
                UPDATE disputes SET response = 'ACCEPT', response_status = 'SENT', response_requested_at = :at,
                       response_sent_at = :at, response_attempts = 1
                 WHERE id = :id""")
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", answered.disputeId())
                .update();
        clock.advance(Duration.ofDays(5));

        assertThat(evidenceDueGauge()).isZero();
        assertThat(list(admin("GET", "/admin/v1/disputes?evidence_due=true", null).body(), "data")).isEmpty();
        assertThat(deadlines.notifyDue()).isZero();
    }

    @Test
    void theDatabaseKeepsResponsesAndEvidenceConsistent() {
        Disputed disputed = open(createMerchant(ALPHA), 100_000, 100_000);
        String fileId = uploaded(disputed, "other", "a.pdf", "application/pdf", PDF);
        assertThat(contest(disputed, List.of(fileId)).status()).isEqualTo(200);
        String update = "UPDATE disputes SET %s WHERE id = ?";

        for (Map.Entry<String, String> broken : Map.of(
                "response_statement = NULL", "ck_dispute_response_content",
                "response_file_ids = '{}'", "ck_dispute_response_content",
                "response = 'ACCEPT'", "ck_dispute_response_content",
                "response_sent_at = NULL", "ck_dispute_response_status",
                "response_status = 'PENDING'", "ck_dispute_response_status",
                "response_failure = 'x'", "ck_dispute_response_status",
                "response_status = 'FAILED'", "ck_dispute_response_status",
                "response_status = NULL", "ck_dispute_response_status",
                "response = NULL, response_statement = NULL, response_file_ids = NULL", "ck_dispute_response_status")
                .entrySet()) {
            assertThat(catchThrowable(() -> jdbc.sql(update.formatted(broken.getKey())).param(1, disputed.disputeId()).update()))
                    .as(broken.getKey()).hasStackTraceContaining(broken.getValue());
        }
        for (Map.Entry<String, String> broken : Map.of(
                "size_bytes = size_bytes + 1", "ck_evidence_ciphertext",
                "category = 'RECEIPT'", "dispute_evidence_files_category_check",
                "content_type = 'text/html'", "dispute_evidence_files_content_type_check").entrySet()) {
            assertThat(catchThrowable(() -> jdbc.sql("UPDATE dispute_evidence_files SET " + broken.getKey() + " WHERE id = ?")
                    .param(1, fileId).update())).as(broken.getKey()).hasStackTraceContaining(broken.getValue());
        }
    }

    private Disputed open(TestMerchant merchant, long paymentAmount, long disputeAmount) {
        Map<String, Object> payment = payAndSucceed(merchant, paymentAmount);
        String provider = str(payment, "latest_attempt.provider");
        Response opened = send("POST", "/simulator/" + provider + "/payments/"
                + str(payment, "latest_attempt.provider_reference") + "/dispute", Map.of(), Map.of("amount", disputeAmount));
        assertThat(opened.status()).as(opened.raw()).isEqualTo(200);
        String pspDisputeId = str(opened.body(), "dispute_id");
        String disputeId = list(get(merchant, "/v1/payments/" + str(payment, "id") + "/disputes").body(), "data").stream()
                .filter(dispute -> pspDisputeId.equals(dispute.get("provider_reference")))
                .map(dispute -> str(dispute, "id"))
                .findFirst().orElseThrow();
        return new Disputed(merchant, str(payment, "id"), disputeId, pspDisputeId, provider);
    }

    private Response upload(Disputed disputed, String category, String fileName, String contentType, byte[] content) {
        return post(disputed.merchant(), "/v1/disputes/" + disputed.disputeId() + "/evidence_files", key(),
                uploadBody(category, fileName, contentType, content));
    }

    private String uploaded(Disputed disputed, String category, String fileName, String contentType, byte[] content) {
        Map<String, Object> body = uploadBody(category, fileName, contentType, content);
        Response uploaded = assertContract("POST", "/v1/disputes/{dispute_id}/evidence_files", body,
                post(disputed.merchant(), "/v1/disputes/" + disputed.disputeId() + "/evidence_files", key(), body));
        assertThat(uploaded.status()).as(uploaded.raw()).isEqualTo(201);
        assertThat(str(uploaded.body(), "sha256")).isEqualTo(sha256(content));
        return str(uploaded.body(), "id");
    }

    private static Map<String, Object> uploadBody(String category, String fileName, String contentType, byte[] content) {
        return Map.of("category", category, "file_name", fileName, "content_type", contentType,
                "content_base64", Base64.getEncoder().encodeToString(content));
    }

    private Response contest(Disputed disputed, List<String> fileIds) {
        return contest(disputed, "The goods were delivered.", fileIds);
    }

    private Response contest(Disputed disputed, String statement, List<String> fileIds) {
        Map<String, Object> body = Map.of("statement", statement, "evidence_file_ids", fileIds);
        Response response = post(disputed.merchant(), "/v1/disputes/" + disputed.disputeId() + "/contest", key(), body);
        if (response.status() < 300) {
            assertContract("POST", "/v1/disputes/{dispute_id}/contest", body, response);
        }
        return response;
    }

    private Response accept(Disputed disputed) {
        return assertContract("POST", "/v1/disputes/{dispute_id}/accept", null,
                post(disputed.merchant(), "/v1/disputes/" + disputed.disputeId() + "/accept", key(), null));
    }

    private Map<String, Object> getDispute(Disputed disputed) {
        return assertContract("GET", "/v1/disputes/{dispute_id}", null,
                get(disputed.merchant(), "/v1/disputes/" + disputed.disputeId())).body();
    }

    private Map<String, Object> mockView(Disputed disputed) {
        Response view = send("GET", "/simulator/" + disputed.provider() + "/disputes/" + disputed.pspDisputeId(), Map.of(), null);
        assertThat(view.status()).as(view.raw()).isEqualTo(200);
        return view.body();
    }

    private List<String> eventTypes(FakeMerchantEndpoint endpoint) {
        deliveryWorker.deliverDue();
        return endpoint.received().stream()
                .peek(delivery -> OpenApiContract.get().assertSchema("Event", delivery.body()))
                .map(delivery -> str(json.read(delivery.body(), new TypeReference<Map<String, Object>>() { }), "type"))
                .filter(type -> type.startsWith("dispute."))
                .toList();
    }

    private Duration nextAttemptIn() {
        Instant next = jdbc.sql("SELECT response_next_attempt_at FROM disputes WHERE response_status = 'PENDING'")
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .single();
        return Duration.between(clock.instant(), next);
    }

    private double evidenceDueGauge() {
        return meters.get("pg.disputes.evidence_due").gauge().value();
    }

    private com.payments.gateway.provider.mock.MockPaymentProvider alpha() {
        return mockProviders.stream().filter(provider -> provider.code().equals(ALPHA)).findFirst().orElseThrow();
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(Hashing.sha256(content));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
