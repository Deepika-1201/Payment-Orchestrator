package com.payments.gateway.merchant;

import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.error.GatewayException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Read-side API of the merchant module used by other modules. */
@Service
public class MerchantDirectory {

    private static final Duration KEY_USAGE_GRANULARITY = Duration.ofHours(1);

    public record ProviderAccount(String merchantId, String providerCode) {
    }

    private final MerchantRepository repository;
    private final SecretCipher cipher;
    private final Clock clock;

    public MerchantDirectory(MerchantRepository repository, SecretCipher cipher, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
    }

    public Merchant require(String merchantId) {
        return repository.findById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
    }

    public Optional<MerchantPrincipal> authenticate(String apiKey) {
        Optional<MerchantRepository.ApiKeyMatch> match = repository.findPrincipalByKeyHash(Hashing.sha256(apiKey));
        match.ifPresent(found -> {
            Instant now = clock.instant();
            if (found.lastUsedAt() == null || !found.lastUsedAt().plus(KEY_USAGE_GRANULARITY).isAfter(now)) {
                repository.touchApiKey(found.principal().apiKeyId(), now);
            }
        });
        return match.map(MerchantRepository.ApiKeyMatch::principal);
    }

    public Set<String> activeProviders(String merchantId) {
        return new LinkedHashSet<>(repository.findActiveProviderCodes(merchantId));
    }

    /** Any status: disabled accounts still settle and reconcile. */
    public boolean hasProviderAccount(String merchantId, String providerCode) {
        return repository.findProviderAccount(merchantId, providerCode).isPresent();
    }

    /** Includes suspended merchants and recently disabled accounts: money already in flight still settles. */
    public List<ProviderAccount> providerAccountsToReconcile(Instant disabledSince) {
        return repository.findAccountsToReconcile(disabledSince);
    }

    /** Current secret first; during a rotation's grace period the previous one follows, so deliveries carry both. */
    public List<String> webhookSecrets(String merchantId) {
        Optional<MerchantRepository.WebhookSecrets> stored = repository.findWebhookSecrets(merchantId);
        if (stored.isEmpty()) {
            return List.of();
        }
        List<String> secrets = new ArrayList<>();
        secrets.add(cipher.decrypt(stored.get().current()));
        Instant previousExpiresAt = stored.get().previousExpiresAt();
        if (stored.get().previous() != null && previousExpiresAt != null && previousExpiresAt.isAfter(clock.instant())) {
            secrets.add(cipher.decrypt(stored.get().previous()));
        }
        return List.copyOf(secrets);
    }
}
