package com.payments.gateway.merchant;

import com.payments.gateway.merchant.OnboardingRepository.Profile;
import com.payments.gateway.merchant.OnboardingRepository.Submission;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.Money;
import java.time.Clock;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OnboardingService {
    public record View(String id, String merchantId, String status, String legalName, String pan, String gstin,
                       String bankAccountLastFour, String bankIfsc, int beneficialOwnerCount, KycDetails.Risk risk,
                       Map<String, KycDetails.Limits> limits, String submittedBy, Instant submittedAt,
                       Instant validUntil, String reviewedBy, Instant reviewedAt, boolean current, boolean expired) {
    }

    public record Overview(String mode, String collectionOwnerId, String activeKycId, List<View> data) {
    }

    private final OnboardingRepository repository;
    private final MerchantRepository merchants;
    private final AggregationProperties properties;
    private final SecretCipher cipher;
    private final JsonCodec json;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OnboardingService(OnboardingRepository repository, MerchantRepository merchants, AggregationProperties properties,
                             SecretCipher cipher, JsonCodec json, AuditLogger audit, TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.merchants = merchants;
        this.properties = properties;
        this.cipher = cipher;
        this.json = json;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public View submit(String merchantId, KycDetails details, String actor) {
        requireEnabled();
        details.verifyCurrent(clock.instant());
        return tx.execute(status -> {
            merchants.lockById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
            if (merchantId.equals(properties.platformMerchantId())) {
                throw invalid("The platform collection owner cannot be an aggregator merchant");
            }
            Profile profile = repository.profile(merchantId, false);
            if (!profile.aggregator() && repository.hasHistory(merchantId)) {
                throw invalid("Activation requires a merchant without payments, mandates or personal PSP accounts");
            }
            String id = Ids.newId("kyc");
            Instant now = clock.instant();
                String serialized = json.write(details);
                Submission submission = new Submission(id, merchantId, cipher.encrypt(serialized, OnboardingRepository.context(id)),
                    "PENDING", actor, now, details.risk().expiresAt(details.verification().verifiedAt()), null, null, Hashing.sha256(serialized));
            repository.insert(submission);
            audit.record("ADMIN", actor, "kyc.submitted", "kyc_submission", id, Map.of("merchant_id", merchantId));
            return view(submission, profile);
        });
    }

    public View review(String merchantId, String submissionId, boolean approve, String actor) {
        requireEnabled();
        return tx.execute(status -> {
            merchants.lockById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
            Submission submission = repository.find(merchantId, submissionId)
                    .orElseThrow(() -> GatewayException.notFound("KYC submission", submissionId));
            Profile profile = repository.profile(merchantId, false);
            String decision = approve ? "APPROVED" : "REJECTED";
            if (!"PENDING".equals(submission.status())) {
                if (decision.equals(submission.status())) {
                    return view(submission, profile);
                }
                throw invalid("A reviewed submission cannot be changed");
            }
            if (submission.submittedBy().equals(actor)) {
                throw new GatewayException(ErrorCode.FORBIDDEN, "A second operator must review the submission");
            }
            Instant now = clock.instant();
            if (approve) {
                details(submission).verifyCurrent(now);
                if (!profile.aggregator() && repository.hasHistory(merchantId)) {
                    throw invalid("Merchant collection ownership is already in use");
                }
                String owner = profile.aggregator() ? profile.collectionOwnerId() : properties.platformMerchantId();
                Merchant platform = merchants.findById(owner).orElseThrow(() -> invalid("The platform merchant does not exist"));
                if (platform.status() != Merchant.Status.ACTIVE || repository.profile(owner, false).aggregator()) {
                    throw invalid("The collection owner must be an active orchestrator merchant");
                }
                List<MerchantRepository.ProviderAccountRow> accounts = merchants.findProviderAccounts(owner).stream()
                        .filter(account -> ProviderAccountService.ACTIVE.equals(account.status())).toList();
                if (accounts.isEmpty()) {
                    throw invalid("The collection owner needs an active PSP account");
                }
                repository.review(submissionId, decision, actor, now);
                repository.activate(merchantId, owner, submissionId);
                accounts.forEach(account -> repository.link(merchantId, account.id(), account.providerCode(), now));
            } else {
                repository.review(submissionId, decision, actor, now);
            }
            audit.record("ADMIN", actor, approve ? "kyc.approved" : "kyc.rejected", "kyc_submission", submissionId,
                    Map.of("merchant_id", merchantId));
            return view(repository.find(merchantId, submissionId).orElseThrow(), repository.profile(merchantId, false));
        });
    }

    public Overview overview(String merchantId) {
        merchants.findById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
        Profile profile = repository.profile(merchantId, false);
        return new Overview(profile.mode(), profile.collectionOwnerId(), profile.activeKycId(),
                repository.list(merchantId).stream().map(submission -> view(submission, profile)).toList());
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = GatewayException.class)
    public void checkCollection(String merchantId, Money amount, String paymentId) {
        Profile profile = repository.profile(merchantId, true);
        if (!profile.aggregator()) {
            return;
        }
        requireEnabled();
        if (!properties.collectionsEnabled()) {
            throw invalid("Aggregator collections are not enabled");
        }
        Submission submission = repository.find(merchantId, profile.activeKycId()).orElseThrow(() -> invalid("Approved KYC is required"));
        if (!"APPROVED".equals(submission.status()) || !submission.validUntil().isAfter(clock.instant())) {
            throw invalid("Current independently approved KYC is required for new collections");
        }
        KycDetails.Limits limits = details(submission).limits().get(amount.currency());
        if (limits == null || amount.amount() > limits.transactionLimit()) {
            throw new GatewayException(ErrorCode.COLLECTION_LIMIT_EXCEEDED, "The merchant's approved transaction limit is exceeded");
        }
        if (paymentId != null && !repository.reserved(paymentId)
                && !repository.reserve(merchantId, paymentId, clock.instant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(),
                        amount.currency(), amount.amount(), limits.dailyLimit())) {
            throw new GatewayException(ErrorCode.COLLECTION_LIMIT_EXCEEDED, "The merchant's approved daily limit is exceeded");
        }
    }

    public boolean isAggregator(String merchantId) {
        return repository.profile(merchantId, false).aggregator();
    }

    private KycDetails details(Submission submission) {
        String plaintext = cipher.decrypt(submission.detailsEncrypted(), OnboardingRepository.context(submission.id()));
        if (!MessageDigest.isEqual(Hashing.sha256(plaintext), submission.detailsHash())) {
            throw new IllegalStateException("KYC payload does not match its immutable submission fingerprint");
        }
        return json.read(plaintext, KycDetails.class);
    }

    private View view(Submission submission, Profile profile) {
        KycDetails details = details(submission);
        String account = details.bankAccount().accountNumber();
        return new View(submission.id(), submission.merchantId(), submission.status(), details.legalName(),
                "******" + details.pan().substring(6), "***********" + details.gstin().substring(11),
                account.substring(account.length() - 4), details.bankAccount().ifsc(), details.beneficialOwners().size(),
                details.risk(), details.limits(), submission.submittedBy(), submission.submittedAt(), submission.validUntil(),
                submission.reviewedBy(), submission.reviewedAt(), submission.id().equals(profile.activeKycId()),
                !submission.validUntil().isAfter(clock.instant()));
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw invalid("Aggregator onboarding is disabled");
        }
    }

    private static GatewayException invalid(String detail) {
        return new GatewayException(ErrorCode.ONBOARDING_INVALID_STATE, detail);
    }
}