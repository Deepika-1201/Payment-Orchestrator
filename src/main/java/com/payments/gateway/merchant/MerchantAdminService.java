package com.payments.gateway.merchant;

import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.net.UrlSafetyValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Merchant onboarding and credential issuance (admin API). Secrets are returned exactly once. */
@Service
public class MerchantAdminService {

    public record CreateMerchantCommand(String name, String webhookUrl, Merchant.LateSuccessPolicy lateSuccessPolicy,
                                        Duration paymentExpiry, List<String> providers) {
    }

    public record CreatedMerchant(Merchant merchant, List<String> providers, String webhookSecret) {
    }

    public record IssuedApiKey(String id, String apiKey, String hint, Instant createdAt) {
    }

    private final MerchantRepository repository;
    private final ProviderRegistry providers;
    private final SecretCipher cipher;
    private final UrlSafetyValidator urlValidator;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MerchantAdminService(MerchantRepository repository, ProviderRegistry providers, SecretCipher cipher,
                                UrlSafetyValidator urlValidator, AuditLogger audit, TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.providers = providers;
        this.cipher = cipher;
        this.urlValidator = urlValidator;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public CreatedMerchant create(CreateMerchantCommand command, String actor) {
        if (command.webhookUrl() != null) {
            urlValidator.check(command.webhookUrl()).ifPresent(reason -> {
                throw GatewayException.validation("webhook_url", reason);
            });
        }
        List<String> providerCodes = command.providers().stream().distinct().toList();
        for (String code : providerCodes) {
            if (!providers.exists(code)) {
                throw GatewayException.validation("providers", "contains unknown provider " + code);
            }
        }
        Instant now = clock.instant();
        Merchant merchant = new Merchant(Ids.newId("mer"), command.name(), Merchant.Status.ACTIVE, command.webhookUrl(),
                command.lateSuccessPolicy(), command.paymentExpiry(), now);
        String webhookSecret = "whsec_" + Hashing.randomToken(32);
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

    public IssuedApiKey issueApiKey(String merchantId, String actor) {
        Merchant merchant = repository.findById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
        String apiKey = "sk_test_" + Hashing.randomToken(32);
        String id = Ids.newId("key");
        String hint = "sk_test_..." + apiKey.substring(apiKey.length() - 4);
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            repository.insertApiKey(id, merchant.id(), Hashing.sha256(apiKey), hint, now);
            audit.record("ADMIN", actor, "api_key.issued", "merchant", merchant.id(), Map.of("api_key_id", id));
        });
        return new IssuedApiKey(id, apiKey, hint, now);
    }
}
