package com.payments.gateway.merchant;

import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.config.SecurityProperties;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.net.UrlSafetyValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Merchant onboarding, settings and credential lifecycle (admin API). Secrets are returned exactly once. */
@Service
public class MerchantAdminService {

    public record CreateMerchantCommand(String name, String webhookUrl, Merchant.LateSuccessPolicy lateSuccessPolicy,
                                        Duration paymentExpiry, List<String> providers) {
    }

    /** Null fields are left unchanged. */
    public record SettingsUpdate(String name, String webhookUrl, Merchant.LateSuccessPolicy lateSuccessPolicy,
                                 Duration paymentExpiry, Long mandateDebitLimit) {
    }

    public record CreatedMerchant(Merchant merchant, List<String> providers, String webhookSecret) {
    }

    public record IssuedApiKey(String id, String apiKey, String hint, String mode, Instant createdAt) {
    }

    public record RotatedSecret(String webhookSecret, Instant previousSecretExpiresAt) {
    }

    private final MerchantRepository repository;
    private final ProviderRegistry providers;
    private final ProviderAccountService providerAccounts;
    private final SecretCipher cipher;
    private final UrlSafetyValidator urlValidator;
    private final SecurityProperties security;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MerchantAdminService(MerchantRepository repository, ProviderRegistry providers,
                                ProviderAccountService providerAccounts, SecretCipher cipher,
                                UrlSafetyValidator urlValidator, SecurityProperties security, AuditLogger audit,
                                TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.providers = providers;
        this.providerAccounts = providerAccounts;
        this.cipher = cipher;
        this.urlValidator = urlValidator;
        this.security = security;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public CreatedMerchant create(CreateMerchantCommand command, String actor) {
        checkWebhookUrl(command.webhookUrl());
        List<String> providerCodes = command.providers() == null ? List.of() : command.providers().stream().distinct().toList();
        for (String code : providerCodes) {
            if (!providers.exists(code)) {
                throw GatewayException.validation("providers", "contains unknown provider " + code);
            }
            if (providerAccounts.requiresCredentials(code)) {
                throw GatewayException.validation("providers", code + " needs credentials: link it with "
                        + "PUT /admin/v1/merchants/{id}/provider-accounts/" + code);
            }
        }
        Instant now = clock.instant();
        Merchant merchant = new Merchant(Ids.newId("mer"), command.name(), Merchant.Status.ACTIVE, null,
                command.webhookUrl(), command.lateSuccessPolicy(), command.paymentExpiry(), now);
        String webhookSecret = newWebhookSecret();
        tx.executeWithoutResult(status -> {
            repository.insert(merchant, cipher.encrypt(webhookSecret));
            for (String code : providerCodes) {
                repository.insertProviderAccount(Ids.newId("mpa"), merchant.id(), code, now);
            }
            audit.record("ADMIN", actor, "merchant.created", "merchant", merchant.id(),
                    Map.of("providers", providerCodes, "late_success_policy", merchant.lateSuccessPolicy().name()));
        });
        return new CreatedMerchant(merchant, providerCodes, webhookSecret);
    }

    public Merchant update(String merchantId, SettingsUpdate update, String actor) {
        checkWebhookUrl(update.webhookUrl());
        return mutate(merchantId, merchant -> merchant.withSettings(
                Objects.requireNonNullElse(update.name(), merchant.name()),
                update.webhookUrl() != null ? update.webhookUrl() : merchant.webhookUrl(),
                Objects.requireNonNullElse(update.lateSuccessPolicy(), merchant.lateSuccessPolicy()),
                Objects.requireNonNullElse(update.paymentExpiry(), merchant.paymentExpiry()))
                .withMandateDebitLimit(update.mandateDebitLimit() == null
                        ? merchant.mandateDebitLimit() : update.mandateDebitLimit()), "merchant.updated", actor);
    }

    /** New events are no longer delivered; deliveries already queued keep the URL they were created with. */
    public Merchant removeWebhookUrl(String merchantId, String actor) {
        return mutate(merchantId, merchant -> merchant.withSettings(merchant.name(), null, merchant.lateSuccessPolicy(),
                merchant.paymentExpiry()), "merchant.webhook_url_removed", actor);
    }

    /**
     * Blocks the merchant's API keys and checkout links. Payments already in flight still complete, refund and
     * reconcile, and their webhooks are still delivered.
     */
    public Merchant suspend(String merchantId, String reason, String actor) {
        return mutate(merchantId, merchant -> merchant.withStatus(Merchant.Status.SUSPENDED, reason), "merchant.suspended", actor);
    }

    public Merchant reactivate(String merchantId, String actor) {
        return mutate(merchantId, merchant -> merchant.withStatus(Merchant.Status.ACTIVE, null), "merchant.reactivated", actor);
    }

    /** The previous secret stays valid for {@code previousValidFor}; deliveries are signed with both meanwhile. */
    public RotatedSecret rotateWebhookSecret(String merchantId, Duration previousValidFor, String actor) {
        String secret = newWebhookSecret();
        Instant now = clock.instant();
        return tx.execute(status -> {
            repository.lockById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
            byte[] current = repository.findWebhookSecrets(merchantId).map(MerchantRepository.WebhookSecrets::current).orElse(null);
            boolean keepPrevious = current != null && !previousValidFor.isZero();
            Instant previousExpiresAt = keepPrevious ? now.plus(previousValidFor) : null;
            repository.replaceWebhookSecret(merchantId, cipher.encrypt(secret), keepPrevious ? current : null,
                    previousExpiresAt, now);
            audit.record("ADMIN", actor, "merchant.webhook_secret_rotated", "merchant", merchantId,
                    Map.of("previous_valid_for_seconds", previousValidFor.toSeconds()));
            return new RotatedSecret(secret, previousExpiresAt);
        });
    }

    public IssuedApiKey issueApiKey(String merchantId, String actor) {
        Merchant merchant = repository.findById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
        SecurityProperties.ApiKeyMode mode = security.apiKeyMode();
        String apiKey = mode.prefix() + Hashing.randomToken(32);
        String id = Ids.newId("key");
        String hint = mode.prefix() + "..." + apiKey.substring(apiKey.length() - 4);
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            repository.insertApiKey(id, merchant.id(), Hashing.sha256(apiKey), hint, mode.name(), now);
            audit.record("ADMIN", actor, "api_key.issued", "merchant", merchant.id(), Map.of("api_key_id", id));
        });
        return new IssuedApiKey(id, apiKey, hint, mode.name(), now);
    }

    public List<MerchantRepository.ApiKeyRow> apiKeys(String merchantId) {
        repository.findById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
        return repository.findApiKeys(merchantId);
    }

    /** Takes effect on the next request: keys are checked against the database on every call. */
    public MerchantRepository.RateLimitOverrides rateLimits(String merchantId) {
        return repository.findRateLimits(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
    }

    /** Null limits fall back to the platform defaults (ADR-020). Takes effect on the merchant's next request. */
    public MerchantRepository.RateLimitOverrides updateRateLimits(String merchantId,
                                                                 MerchantRepository.RateLimitOverrides limits,
                                                                 String actor) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            repository.lockById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
            MerchantRepository.RateLimitOverrides before = repository.findRateLimits(merchantId).orElseThrow();
            if (!before.equals(limits)) {
                repository.updateRateLimits(merchantId, limits, now);
                Map<String, Object> details = new java.util.HashMap<>();
                details.put("read", describe(limits.read()));
                details.put("write", describe(limits.write()));
                audit.record("ADMIN", actor, "merchant.rate_limits_updated", "merchant", merchantId, details);
            }
            return limits;
        });
    }

    private static String describe(RateLimit limit) {
        return limit == null ? "default" : limit.perSecond() + "/s burst " + limit.burst();
    }

    public MerchantRepository.ApiKeyRow revokeApiKey(String merchantId, String keyId, String actor) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            if (repository.revokeApiKey(merchantId, keyId, now)) {
                audit.record("ADMIN", actor, "api_key.revoked", "merchant", merchantId, Map.of("api_key_id", keyId));
            }
            return repository.findApiKeys(merchantId).stream().filter(key -> key.id().equals(keyId)).findFirst()
                    .orElseThrow(() -> GatewayException.notFound("API key", keyId));
        });
    }

    private Merchant mutate(String merchantId, UnaryOperator<Merchant> change, String action,
                            String actor) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            Merchant before = repository.lockById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
            Merchant after = change.apply(before);
            Map<String, Object> changed = changes(before, after);
            if (!changed.isEmpty()) {
                repository.updateSettings(after, now);
                audit.record("ADMIN", actor, action, "merchant", merchantId, changed);
            }
            return after;
        });
    }

    /** Audit details: which fields changed, with values except for the webhook URL (it may embed a token). */
    private static Map<String, Object> changes(Merchant before, Merchant after) {
        Map<String, Object> changed = new LinkedHashMap<>();
        if (!before.name().equals(after.name())) {
            changed.put("name", after.name());
        }
        if (!Objects.equals(before.webhookUrl(), after.webhookUrl())) {
            changed.put("webhook_url", after.webhookUrl() == null ? "removed" : "changed");
        }
        if (before.lateSuccessPolicy() != after.lateSuccessPolicy()) {
            changed.put("late_success_policy", after.lateSuccessPolicy().name());
        }
        if (!before.paymentExpiry().equals(after.paymentExpiry())) {
            changed.put("payment_expiry_seconds", after.paymentExpiry().toSeconds());
        }
        if (!Objects.equals(before.mandateDebitLimit(), after.mandateDebitLimit())) {
            changed.put("mandate_debit_limit", after.mandateDebitLimit());
        }
        if (before.status() != after.status() || !Objects.equals(before.statusReason(), after.statusReason())) {
            List<String> status = new ArrayList<>(List.of(after.status().name()));
            Optional.ofNullable(after.statusReason()).ifPresent(status::add);
            changed.put("status", status);
        }
        return changed;
    }

    private void checkWebhookUrl(String url) {
        if (url != null) {
            urlValidator.check(url).ifPresent(reason -> {
                throw GatewayException.validation("webhook_url", reason);
            });
        }
    }

    private static String newWebhookSecret() {
        return "whsec_" + Hashing.randomToken(32);
    }
}
