package com.payments.gateway.merchant;

import com.payments.gateway.shared.crypto.Hashing;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.error.GatewayException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Read-side API of the merchant module used by other modules. */
@Service
public class MerchantDirectory {

    public record ProviderAccount(String merchantId, String providerCode) {
    }

    private final MerchantRepository repository;
    private final SecretCipher cipher;

    public MerchantDirectory(MerchantRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public Merchant require(String merchantId) {
        return repository.findById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
    }

    public Optional<MerchantPrincipal> authenticate(String apiKey) {
        return repository.findPrincipalByKeyHash(Hashing.sha256(apiKey));
    }

    public Set<String> activeProviders(String merchantId) {
        return new LinkedHashSet<>(repository.findActiveProviderCodes(merchantId));
    }

    public List<ProviderAccount> activeProviderAccounts() {
        return repository.findAllActiveProviderAccounts();
    }

    public Optional<String> webhookSecret(String merchantId) {
        return repository.findWebhookSecret(merchantId).map(cipher::decrypt);
    }
}
