package com.payments.gateway.merchant;

import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.provider.mock.MockPaymentProvider;
import com.payments.gateway.payment.application.MandateScheduler;
import com.payments.gateway.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static com.payments.gateway.support.JsonPath.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"pg.aggregation.enabled=true", "pg.aggregation.platform-merchant-id=platform-test",
        "pg.aggregation.collections-enabled=true"})
class OnboardingIntegrationTest extends IntegrationTest {
    @Autowired
    private OnboardingService onboarding;
    @Autowired
    private OnboardingRepository repository;
    @Autowired
    private SecretCipher cipher;
    @Autowired
    private TransactionTemplate tx;
        @Autowired
        private ProviderAccountService accounts;
        @Autowired
        private MerchantDirectory directory;
        @Autowired
        private MandateScheduler scheduler;
    @MockitoSpyBean
    private AggregationProperties properties;

    private TestMerchant platform;
    private TestMerchant merchant;

    @BeforeEach
    void collectionOwner() {
        platform = createMerchant(ALPHA);
        merchant = createMerchant();
        doReturn(platform.id()).when(properties).platformMerchantId();
    }

    @Test
    void encryptedSubmissionIndependentApprovalAndFrozenOwnership() {
        KycDetails details = KycDetailsTest.details(KycDetails.Risk.HIGH, clock.instant());
        Response created = admin("POST", path(), details);
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        String submissionId = str(created.body(), "id");
        assertThat(created.raw()).doesNotContain(details.pan(), details.gstin(), details.bankAccount().accountNumber(),
                details.beneficialOwners().getFirst().pan(), details.verification().kycReference());
        var stored = repository.find(merchant.id(), submissionId).orElseThrow();
        assertThat(new String(stored.detailsEncrypted(), StandardCharsets.ISO_8859_1)).doesNotContain(details.pan());
        assertThat(cipher.decrypt(stored.detailsEncrypted(), OnboardingRepository.context(submissionId))).contains(details.pan());
        assertThatThrownBy(() -> cipher.decrypt(stored.detailsEncrypted(), "merchant_kyc:another"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(admin("POST", path() + "/" + submissionId + "/approve", null).status()).isEqualTo(403);
        Response approved = finance("POST", path() + "/" + submissionId + "/approve", null);
        assertThat(approved.status()).as(approved.raw()).isEqualTo(200);
        assertThat(approved.body().get("current")).isEqualTo(true);
        assertThat(repository.profile(merchant.id(), false).collectionOwnerId()).isEqualTo(platform.id());
        assertThat(jdbc.sql("SELECT credential_owner_id FROM merchant_provider_accounts WHERE merchant_id = :id")
                .param("id", merchant.id()).query(String.class).single()).isNotBlank();
        assertThatThrownBy(() -> jdbc.sql("UPDATE merchant_kyc_submissions SET submitted_by = 'other' WHERE id = :id")
                .param("id", submissionId).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE merchants SET mode = 'ORCHESTRATOR', collection_owner_id = NULL, active_kyc_id = NULL WHERE id = :id")
                .param("id", merchant.id()).update()).hasMessageContaining("ownership cannot change");
        assertThatThrownBy(() -> jdbc.sql("UPDATE merchant_provider_accounts SET credential_owner_id = NULL WHERE merchant_id = :id")
                .param("id", merchant.id()).update()).hasMessageContaining("immutable");
        assertThat(finance("POST", path() + "/" + submissionId + "/approve", null).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'kyc.approved'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void historyAndDisabledPlatformPreventActivation() {
        createPayment(merchant, 1000, "automatic");
        assertThatThrownBy(() -> onboarding.submit(merchant.id(), KycDetailsTest.details(KycDetails.Risk.LOW, clock.instant()), "maker"))
                .isInstanceOf(GatewayException.class).hasMessageContaining("without payments");
        TestMerchant unused = createMerchant();
        var submission = onboarding.submit(unused.id(), KycDetailsTest.details(KycDetails.Risk.LOW, clock.instant()), "maker");
        createPayment(unused, 1000, "automatic");
        assertThatThrownBy(() -> onboarding.review(unused.id(), submission.id(), true, "checker"))
                .isInstanceOf(GatewayException.class).hasMessageContaining("already in use");
        assertThat(repository.find(unused.id(), submission.id()).orElseThrow().status()).isEqualTo("PENDING");
        TestMerchant fresh = createMerchant();
        var freshSubmission = onboarding.submit(fresh.id(), KycDetailsTest.details(KycDetails.Risk.LOW, clock.instant()), "maker");
        jdbc.sql("UPDATE merchants SET status = 'SUSPENDED', status_reason = 'compliance review' WHERE id = :id")
                .param("id", platform.id()).update();
        assertThatThrownBy(() -> onboarding.review(fresh.id(), freshSubmission.id(), true, "checker"))
                .hasMessageContaining("active orchestrator");
        jdbc.sql("UPDATE merchants SET status = 'ACTIVE', status_reason = NULL WHERE id = :id").param("id", platform.id()).update();
        accounts.disable(platform.id(), ALPHA, "operator");
        assertThatThrownBy(() -> onboarding.review(fresh.id(), freshSubmission.id(), true, "checker"))
                .hasMessageContaining("active PSP account");
        assertThatThrownBy(() -> onboarding.submit(platform.id(), KycDetailsTest.details(KycDetails.Risk.LOW, clock.instant()), "maker"))
                .hasMessageContaining("collection owner cannot");
    }

    @Test
    void expiryBlocksCollectionsAndPendingRefreshDoesNotReplaceApproval() {
        var submission = onboarding.submit(merchant.id(), KycDetailsTest.details(KycDetails.Risk.HIGH, clock.instant()), "maker");
        onboarding.review(merchant.id(), submission.id(), true, "checker");
        var refresh = onboarding.submit(merchant.id(), KycDetailsTest.details(KycDetails.Risk.LOW, clock.instant()), "maker");
        var expiresBeforeReview = onboarding.submit(merchant.id(), KycDetailsTest.details(KycDetails.Risk.HIGH, clock.instant()), "maker");
        assertThat(repository.profile(merchant.id(), false).activeKycId()).isEqualTo(submission.id());
        tx.executeWithoutResult(status -> onboarding.checkCollection(merchant.id(), new com.payments.gateway.shared.model.Money(1000, "INR"), null));
        clock.advance(Duration.ofDays(180));
        assertThatThrownBy(() -> onboarding.review(merchant.id(), expiresBeforeReview.id(), true, "checker"))
                .hasMessageContaining("risk refresh period");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> onboarding.checkCollection(merchant.id(),
                new com.payments.gateway.shared.model.Money(1000, "INR"), null))).isInstanceOf(GatewayException.class)
                .hasMessageContaining("Current independently approved KYC");
        onboarding.review(merchant.id(), refresh.id(), true, "checker");
        tx.executeWithoutResult(status -> onboarding.checkCollection(merchant.id(), new com.payments.gateway.shared.model.Money(1000, "INR"), null));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> onboarding.checkCollection(merchant.id(),
                new com.payments.gateway.shared.model.Money(100_001, "INR"), null))).isInstanceOf(GatewayException.class)
                .hasMessageContaining("transaction limit");
    }

        @Test
        void collectionsUseLinkedCredentialsAndExpiryDoesNotPreventRefunds() {
                var submission = onboarding.submit(merchant.id(), KycDetailsTest.details(KycDetails.Risk.HIGH, clock.instant()), "maker");
                onboarding.review(merchant.id(), submission.id(), true, "checker");
                Map<String, Object> paid = payAndSucceed(merchant, 10_000);
                String paymentId = str(paid, "id");
                assertThat(jdbc.sql("SELECT used_amount FROM aggregation_daily_volume WHERE merchant_id = :id")
                                .param("id", merchant.id()).query(Long.class).single()).isEqualTo(10_000);
                clock.advance(Duration.ofDays(180));
                Response create = post(merchant, "/v1/payments", "expired-kyc",
                        Map.of("amount", 1000, "currency", "INR", "merchant_order_id", "expired-kyc"));
                assertThat(create.status()).as(create.raw()).isEqualTo(409);
                Response refund = post(merchant, "/v1/payments/" + paymentId + "/refunds", "expired-refund", Map.of("amount", 1000));
                assertThat(refund.status()).as(refund.raw()).isEqualTo(201);
                assertThat(str(refund.body(), "status")).isEqualTo("succeeded");
        }

        @Test
        void confirmationAndMandateAdmissionRecheckKycButAuthorizedCaptureCanComplete() {
                var submission = onboarding.submit(merchant.id(), KycDetailsTest.details(KycDetails.Risk.HIGH,
                                clock.instant().minus(Duration.ofDays(180)).plusSeconds(60)), "maker");
                onboarding.review(merchant.id(), submission.id(), true, "checker");
                String pendingId = str(createPayment(merchant, 1000, "automatic"), "id");
                String authorizedId = str(createPayment(merchant, 1000, "manual"), "id");
                Response confirmation = confirm(merchant, authorizedId, card());
                assertThat(confirmation.status()).as(confirmation.raw()).isEqualTo(200);
                simulate(ALPHA, str(confirmation.body(), "latest_attempt.provider_reference"), "success", false);
                assertThat(str(getPayment(merchant, authorizedId), "status")).isEqualTo("authorized");
                clock.advance(Duration.ofSeconds(60));
                Response refused = confirm(merchant, pendingId, card());
                assertThat(refused.status()).as(refused.raw()).isEqualTo(409);
                assertThat(str(refused.body(), "code")).isEqualTo("onboarding_invalid_state");
                Response capture = post(merchant, "/v1/payments/" + authorizedId + "/capture", "expired-capture", null);
                assertThat(capture.status()).as(capture.raw()).isEqualTo(200);
                assertThat(str(capture.body(), "status")).isEqualTo("succeeded");
                Response registration = post(merchant, "/v1/mandates", UUID.randomUUID().toString(), Map.of(
                                "instrument", "enach", "max_amount", 10_000, "currency", "INR", "frequency", "monthly",
                                "customer", Map.of("reference", "customer", "name", "Example Customer", "email", "buyer@example.com", "phone", "+919999999999"),
                                "return_url", "https://merchant.example/return"));
                assertThat(registration.status()).as(registration.raw()).isEqualTo(409);
                assertThat(str(registration.body(), "code")).isEqualTo("onboarding_invalid_state");
        }

            @Test
            void keyRotationPreservesFactsButChangingEncryptedFactsIsDetected() {
                var submission = enroll();
                var stored = repository.find(merchant.id(), submission.id()).orElseThrow();
                String plaintext = cipher.decrypt(stored.detailsEncrypted(), OnboardingRepository.context(submission.id()));
                byte[] replacement = cipher.encrypt(plaintext, OnboardingRepository.context(submission.id()));
                assertThat(repository.swapKeyRingCiphertext(submission.id(), stored.detailsEncrypted(), replacement)).isTrue();
                assertThat(repository.swapKeyRingCiphertext(submission.id(), stored.detailsEncrypted(), replacement)).isFalse();
                assertThat(onboarding.overview(merchant.id()).activeKycId()).isEqualTo(submission.id());
                assertThat(repository.keyRingCiphertexts()).anyMatch(row -> row.rowId().equals(submission.id()));
                byte[] forged = cipher.encrypt(plaintext.replace("Example Merchant", "Different Merchant"), OnboardingRepository.context(submission.id()));
                assertThat(repository.swapKeyRingCiphertext(submission.id(), replacement, forged)).isTrue();
                assertThatThrownBy(() -> onboarding.overview(merchant.id())).hasMessageContaining("fingerprint");
                assertThatThrownBy(() -> jdbc.sql("UPDATE merchant_kyc_submissions SET details_hash = :hash WHERE id = :id")
                        .param("hash", com.payments.gateway.shared.crypto.Hashing.sha256("changed"))
                        .param("id", submission.id()).update()).hasMessageContaining("immutable");
            }

            @Test
            void platformCredentialsRotateWithoutCopyingAndDisabledOwnersStopRouting() {
                enroll();
                accounts.link(platform.id(), ALPHA, Map.of("api_key", "platform_key_original", "webhook_secret", "platform_secret_original"), "operator");
                assertThat(accounts.require(merchant.id(), ALPHA).credential("api_key")).contains("platform_key_original");
                accounts.link(platform.id(), ALPHA, Map.of("api_key", "platform_key_rotated", "webhook_secret", "platform_secret_rotated"), "operator");
                assertThat(accounts.require(merchant.id(), ALPHA).credential("api_key")).contains("platform_key_rotated");
                assertThat(accounts.list(merchant.id()).getFirst().credentials()).isEmpty();
                assertThatThrownBy(() -> accounts.link(merchant.id(), ALPHA, Map.of("api_key", "personal_key"), "operator"))
                        .isInstanceOf(GatewayException.class);
                jdbc.sql("UPDATE merchants SET status = 'SUSPENDED', status_reason = 'compliance review' WHERE id = :id")
                        .param("id", platform.id()).update();
                assertThat(directory.activeProviders(merchant.id())).isEmpty();
                jdbc.sql("UPDATE merchants SET status = 'ACTIVE', status_reason = NULL WHERE id = :id").param("id", platform.id()).update();
                accounts.disable(platform.id(), ALPHA, "operator");
                assertThat(directory.activeProviders(merchant.id())).isEmpty();
                assertThat(accounts.require(merchant.id(), ALPHA).credential("api_key")).contains("platform_key_rotated");
                assertThatThrownBy(() -> accounts.link(merchant.id(), ALPHA, null, "operator")).isInstanceOf(GatewayException.class);
            }

            @Test
            void onlyTheLinkedOwnerCanSendSignedPaymentEvents() {
                enroll();
                TestMerchant stranger = createMerchant(ALPHA);
                String paymentId = str(createPayment(merchant, 2000, "automatic"), "id");
                Response confirmation = confirm(merchant, paymentId, upi("intent"));
                String reference = str(confirmation.body(), "latest_attempt.provider_reference");
                Map<String, Object> event = Map.of("event_id", "evt-owner-scope", "type", "payment.updated",
                        "provider_reference", reference, "merchant_reference", str(confirmation.body(), "latest_attempt.id"),
                        "status", "captured", "amount", 2000, "currency", "INR");
                assertThat(webhook(stranger, event).status()).isEqualTo(200);
                assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("requires_action");
                assertThat(webhook(platform, event).status()).isEqualTo(200);
                assertThat(str(getPayment(merchant, paymentId), "status")).isEqualTo("succeeded");
            }

            @Test
            void ownerScopeAlsoCoversRefundDisputeCreditAndMandateEvents() {
                enroll();
                TestMerchant stranger = createMerchant(ALPHA);
                Map<String, Object> paid = payAndSucceed(merchant, 20_000);
                String paymentId = str(paid, "id");
                Response refund = post(merchant, "/v1/payments/" + paymentId + "/refunds", "pending-refund", Map.of("amount", 1007));
                assertThat(refund.status()).as(refund.raw()).isEqualTo(201);
                Map<String, Object> event = Map.of("event_id", "owner-refund", "type", "refund.updated", "status", "succeeded",
                        "provider_reference", str(refund.body(), "provider_reference"), "merchant_reference", str(refund.body(), "id"),
                        "amount", 1007, "currency", "INR");
                assertThat(webhook(stranger, event).status()).isEqualTo(200);
                assertThat(str(get(merchant, "/v1/refunds/" + str(refund.body(), "id")).body(), "status")).isEqualTo("pending");
                assertThat(webhook(platform, event).status()).isEqualTo(200);
                assertThat(str(get(merchant, "/v1/refunds/" + str(refund.body(), "id")).body(), "status")).isEqualTo("succeeded");
                event = Map.of("event_id", "owner-dispute", "type", "dispute.updated", "status", "open",
                        "provider_reference", "provider_dispute", "payment_reference", str(paid, "latest_attempt.provider_reference"),
                        "amount", 2000, "currency", "INR");
                assertThat(webhook(stranger, event).status()).isEqualTo(200);
                assertThat(count("SELECT count(*) FROM disputes")).isZero();
                assertThat(webhook(platform, event).status()).isEqualTo(200);
                assertThat(count("SELECT count(*) FROM disputes")).isEqualTo(1);
                String transferId = str(createPayment(merchant, 3000, "automatic"), "id");
                Response transfer = confirm(merchant, transferId, Map.of("type", "bank_transfer"));
                assertThat(transfer.status()).as(transfer.raw()).isEqualTo(200);
                event = Map.of("event_id", "owner-credit", "type", "collection.credited", "status", "received",
                        "provider_reference", "provider_credit", "payment_reference", str(transfer.body(), "latest_attempt.provider_reference"),
                        "amount", 3000, "currency", "INR", "transfer_mode", "neft");
                assertThat(webhook(stranger, event).status()).isEqualTo(200);
                assertThat(count("SELECT count(*) FROM transfer_credits")).isZero();
                assertThat(webhook(platform, event).status()).isEqualTo(200);
                assertThat(str(getPayment(merchant, transferId), "status")).isEqualTo("succeeded");
                Response registration = post(merchant, "/v1/mandates", UUID.randomUUID().toString(), Map.of(
                        "instrument", "upi_autopay", "max_amount", 10_000, "currency", "INR", "frequency", "monthly",
                        "customer", Map.of("reference", "customer", "name", "Example Customer", "email", "buyer@example.com", "phone", "+919999999999"),
                        "return_url", "https://merchant.example/return"));
                assertThat(registration.status()).as(registration.raw()).isEqualTo(201);
                String mandateId = str(registration.body(), "id");
                String mandateReference = jdbc.sql("SELECT provider_reference FROM mandates WHERE id = :id").param("id", mandateId).query(String.class).single();
                event = Map.of("event_id", "owner-mandate", "type", "mandate.updated", "status", "active",
                        "provider_reference", mandateReference, "mandate_reference", "approved-token");
                assertThat(webhook(stranger, event).status()).isEqualTo(200);
                assertThat(str(get(merchant, "/v1/mandates/" + mandateId).body(), "status")).isEqualTo("pending_authorization");
                assertThat(webhook(platform, event).status()).isEqualTo(200);
                assertThat(str(get(merchant, "/v1/mandates/" + mandateId).body(), "status")).isEqualTo("active");
                assertThat(send("POST", "/simulator/" + ALPHA + "/mandates/" + mandateReference + "/complete", Map.of(),
                        Map.of("outcome", "success")).status()).isEqualTo(200);
                Response debit = post(merchant, "/v1/mandates/" + mandateId + "/debits", "owner-notification-debit",
                        Map.of("amount", 1000, "merchant_debit_id", "owner-notification"));
                assertThat(debit.status()).as(debit.raw()).isEqualTo(201);
                String debitId = str(debit.body(), "id");
                var due = jdbc.sql("SELECT next_action_at FROM mandate_debits WHERE id = :id").param("id", debitId)
                        .query(java.time.OffsetDateTime.class).single().toInstant();
                if (due.isAfter(clock.instant())) {
                    clock.set(due);
                }
                scheduler.processDueDebits();
                String notification = jdbc.sql("SELECT notification_reference FROM mandate_debits WHERE id = :id")
                        .param("id", debitId).query(String.class).single();
                event = Map.of("event_id", "owner-notification", "type", "notification.updated", "status", "failed",
                        "provider_reference", notification, "failure_message", "Delivery failed");
                assertThat(webhook(stranger, event).status()).isEqualTo(200);
                assertThat(str(get(merchant, "/v1/mandates/" + mandateId + "/debits/" + debitId).body(), "status")).isEqualTo("notifying");
                assertThat(webhook(platform, event).status()).isEqualTo(200);
                assertThat(str(get(merchant, "/v1/mandates/" + mandateId + "/debits/" + debitId).body(), "status")).isEqualTo("failed");
            }

            @Test
            void permissionsRejectionsFeatureGatesAndUnlinkedCurrenciesAreEnforced() {
                KycDetails details = KycDetailsTest.details(KycDetails.Risk.HIGH, clock.instant());
                assertThat(send("POST", path(), Map.of("Authorization", "Bearer test-readonly-token"), details).status()).isEqualTo(403);
                Response submitted = admin("POST", path(), details);
                String id = str(submitted.body(), "id");
                assertThat(send("POST", path() + "/" + id + "/approve", Map.of("Authorization", "Bearer test-ops-token"), null).status()).isEqualTo(403);
                assertThat(finance("POST", path() + "/" + id + "/reject", null).status()).isEqualTo(200);
                assertThat(finance("POST", path() + "/" + id + "/approve", null).status()).isEqualTo(409);
                enroll();
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> onboarding.checkCollection(merchant.id(), new Money(100, "USD"), null)))
                        .isInstanceOf(GatewayException.class).hasMessageContaining("transaction limit");
                doReturn(false).when(properties).collectionsEnabled();
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> onboarding.checkCollection(merchant.id(), new Money(100, "INR"), null)))
                        .hasMessageContaining("collections are not enabled");
                doReturn(false).when(properties).enabled();
                assertThatThrownBy(() -> onboarding.submit(merchant.id(), details, "maker")).hasMessageContaining("onboarding is disabled");
            }

            @Test
            void databaseRequiresIndependentReviewAndRejectsHistoricalModeChanges() {
                var submission = onboarding.submit(merchant.id(), KycDetailsTest.details(KycDetails.Risk.HIGH, clock.instant()), "maker");
                assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE merchant_kyc_submissions SET status = 'APPROVED', reviewed_by = submitted_by, reviewed_at = submitted_at
                         WHERE id = :id
                        """).param("id", submission.id()).update()).hasMessageContaining("ck_kyc_review");
                jdbc.sql("""
                        UPDATE merchant_kyc_submissions SET status = 'APPROVED', reviewed_by = 'checker', reviewed_at = submitted_at
                         WHERE id = :id
                        """).param("id", submission.id()).update();
                createPayment(merchant, 1000, "automatic");
                assertThatThrownBy(() -> repository.activate(merchant.id(), platform.id(), submission.id()))
                        .hasMessageContaining("ownership cannot change");
            }

            @Test
            void databaseModeCannotBeRevertedBeforeAnyFinancialActivity() {
                var submission = onboarding.submit(merchant.id(), KycDetailsTest.details(KycDetails.Risk.HIGH, clock.instant()), "maker");
                jdbc.sql("""
                        UPDATE merchant_kyc_submissions SET status = 'APPROVED', reviewed_by = 'checker', reviewed_at = submitted_at
                         WHERE id = :id
                        """).param("id", submission.id()).update();
                repository.activate(merchant.id(), platform.id(), submission.id());
                assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE merchants SET mode = 'ORCHESTRATOR', collection_owner_id = NULL, active_kyc_id = NULL WHERE id = :id
                        """).param("id", merchant.id()).update()).hasMessageContaining("ownership cannot change");
            }

            @Test
            void concurrentReservationsRespectTheDailyLimitAndRollbackAtomically() throws Exception {
                enroll();
                payAndSucceed(merchant, 100_000);
                String first = str(createPayment(merchant, 100_000, "automatic"), "id");
                String second = str(createPayment(merchant, 100_000, "automatic"), "id");
                List<Future<Response>> pending = new ArrayList<>();
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    tx.executeWithoutResult(status -> {
                        jdbc.sql("SELECT used_amount FROM aggregation_daily_volume WHERE merchant_id = :merchant FOR UPDATE")
                                .param("merchant", merchant.id()).query(Long.class).single();
                        pending.add(executor.submit(() -> confirm(merchant, first, upi("intent"))));
                        pending.add(executor.submit(() -> confirm(merchant, second, upi("intent"))));
                        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                                SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'
                                """).query(Long.class).single() >= 2);
                    });
                    List<Integer> statuses = List.of(pending.get(0).get(10, TimeUnit.SECONDS).status(),
                            pending.get(1).get(10, TimeUnit.SECONDS).status());
                    assertThat(statuses).containsExactlyInAnyOrder(200, 422);
                }
                assertThat(jdbc.sql("SELECT used_amount FROM aggregation_daily_volume WHERE merchant_id = :id")
                        .param("id", merchant.id()).query(Long.class).single()).isEqualTo(200_000);
                String rolledBack = str(createPayment(merchant, 50_000, "automatic"), "id");
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    onboarding.checkCollection(merchant.id(), new Money(50_000, "INR"), rolledBack);
                    throw new IllegalStateException("rollback probe");
                })).hasMessageContaining("rollback probe");
                assertThat(repository.reserved(rolledBack)).isFalse();
                assertThat(confirm(merchant, rolledBack, upi("intent")).status()).isEqualTo(200);
                tx.executeWithoutResult(status -> onboarding.checkCollection(merchant.id(), new Money(50_000, "INR"), rolledBack));
                assertThat(jdbc.sql("SELECT used_amount FROM aggregation_daily_volume WHERE merchant_id = :id")
                        .param("id", merchant.id()).query(Long.class).single()).isEqualTo(250_000);
            }

            @Test
            void scheduledDebitFailsCleanlyWhenKycExpiresBeforeExecution() {
                enroll();
                Response registration = post(merchant, "/v1/mandates", UUID.randomUUID().toString(), Map.of(
                        "instrument", "enach", "max_amount", 10_000, "currency", "INR", "frequency", "monthly",
                        "customer", Map.of("reference", "customer", "name", "Example Customer", "email", "buyer@example.com", "phone", "+919999999999"),
                        "return_url", "https://merchant.example/return"));
                assertThat(registration.status()).as(registration.raw()).isEqualTo(201);
                String mandateId = str(registration.body(), "id");
                String reference = jdbc.sql("SELECT provider_reference FROM mandates WHERE id = :id").param("id", mandateId).query(String.class).single();
                assertThat(send("POST", "/simulator/" + ALPHA + "/mandates/" + reference + "/complete", Map.of(),
                        Map.of("outcome", "success")).status()).isEqualTo(200);
                Response scheduled = post(merchant, "/v1/mandates/" + mandateId + "/debits", UUID.randomUUID().toString(),
                        Map.of("amount", 1000, "merchant_debit_id", "after-expiry", "due_at", clock.instant().plus(Duration.ofDays(180)).toString()));
                assertThat(scheduled.status()).as(scheduled.raw()).isEqualTo(201);
                clock.advance(Duration.ofDays(180));
                scheduler.processDueDebits();
                Map<String, Object> debit = get(merchant, "/v1/mandates/" + mandateId + "/debits/" + str(scheduled.body(), "id")).body();
                assertThat(str(debit, "status")).as(debit.toString()).isEqualTo("failed");
                assertThat(str(getPayment(merchant, str(debit, "payment_id")), "status")).isEqualTo("failed");
                Response another = post(merchant, "/v1/mandates/" + mandateId + "/debits", UUID.randomUUID().toString(),
                        Map.of("amount", 1000, "merchant_debit_id", "created-after-expiry", "due_at", clock.instant().plus(Duration.ofDays(32)).toString()));
                assertThat(another.status()).as(another.raw()).isEqualTo(409);
                assertThat(str(another.body(), "code")).isEqualTo("onboarding_invalid_state");
            }

            private OnboardingService.View enroll() {
                var submission = onboarding.submit(merchant.id(), KycDetailsTest.details(KycDetails.Risk.HIGH, clock.instant()), "maker");
                return onboarding.review(merchant.id(), submission.id(), true, "checker");
            }

            private Response webhook(TestMerchant owner, Map<String, Object> event) {
                String body = json.write(event);
                MockPaymentProvider provider = mockProviders.stream().filter(mock -> ALPHA.equals(mock.code())).findFirst().orElseThrow();
                return send("POST", "/v1/webhooks/providers/" + ALPHA + "/" + accounts.require(owner.id(), ALPHA).id(),
                        Map.of(MockPaymentProvider.SIGNATURE_HEADER, provider.sign(clock.instant().getEpochSecond(), body)), body);
            }

    private String path() {
        return "/admin/v1/merchants/" + merchant.id() + "/kyc";
    }

    private Response finance(String method, String path, Object body) {
        return send(method, path, Map.of("Authorization", "Bearer test-finance-token"), body);
    }
}