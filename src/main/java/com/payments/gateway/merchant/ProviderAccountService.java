package com.payments.gateway.merchant;

import com.payments.gateway.merchant.MerchantRepository.ProviderAccountRow;
import com.payments.gateway.provider.MerchantAccountResolver;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.CredentialField;
import com.payments.gateway.provider.spi.MerchantAccount;
import com.payments.gateway.provider.spi.PaymentProvider;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.crypto.SecretCipher;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.json.JsonCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

/**
 * Merchants' own PSP accounts (ADR-014). Credentials are checked against the adapter's declared fields, encrypted
 * bound to the account id, and never returned: views show non-secret values and the last 4 characters of secrets.
 */
@Service
public class ProviderAccountService implements MerchantAccountResolver {

    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";
    private static final int MAX_CREDENTIAL_LENGTH = 4096;
    private static final TypeReference<Map<String, String>> CREDENTIALS = new TypeReference<>() {
    };

    public record AccountView(String id, String providerCode, String status, Map<String, String> credentials,
                              Instant credentialsUpdatedAt, Instant createdAt, Instant disabledAt) {
    }

    private final MerchantRepository repository;
    private final ProviderRegistry providers;
    private final SecretCipher cipher;
    private final JsonCodec json;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ProviderAccountService(MerchantRepository repository, ProviderRegistry providers, SecretCipher cipher,
                                  JsonCodec json, AuditLogger audit, TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.providers = providers;
        this.cipher = cipher;
        this.json = json;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public List<AccountView> list(String merchantId) {
        repository.findById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
        return repository.findProviderAccounts(merchantId).stream().map(this::view).toList();
    }

    /** Links or re-enables an account. {@code credentials}, when given, replace everything stored before. */
    public AccountView link(String merchantId, String providerCode, Map<String, String> credentials, String actor) {
        PaymentProvider provider = providers.find(providerCode)
                .orElseThrow(() -> GatewayException.notFound("Provider", providerCode));
        if (credentials != null) {
            validate(provider, credentials);
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            repository.lockById(merchantId).orElseThrow(() -> GatewayException.notFound("Merchant", merchantId));
            Optional<ProviderAccountRow> existing = repository.findProviderAccount(merchantId, providerCode);
            String id = existing.map(ProviderAccountRow::id).orElseGet(() -> Ids.newId("mpa"));
            if (existing.isEmpty()) {
                repository.insertProviderAccount(id, merchantId, providerCode, now);
            }
            byte[] encrypted = existing.map(ProviderAccountRow::credentialsEncrypted).orElse(null);
            Instant credentialsUpdatedAt = existing.map(ProviderAccountRow::credentialsUpdatedAt).orElse(null);
            if (credentials != null) {
                encrypted = cipher.encrypt(json.write(new TreeMap<>(credentials)), context(id));
                credentialsUpdatedAt = now;
            }
            List<String> missing = missingRequired(provider, encrypted == null ? Map.of() : decrypt(id, encrypted));
            if (!missing.isEmpty()) {
                throw GatewayException.validation("credentials", "must include " + String.join(", ", missing));
            }
            repository.updateProviderAccount(id, ACTIVE, encrypted, credentialsUpdatedAt, null);
            audit.record("ADMIN", actor, existing.isEmpty() ? "provider_account.linked" : "provider_account.updated",
                    "provider_account", id, Map.of("merchant_id", merchantId, "provider", providerCode,
                            "credential_fields", credentials == null ? List.of() : new TreeSet<>(credentials.keySet())));
            return view(repository.findProviderAccountById(id).orElseThrow());
        });
    }

    /** Stops routing new payments to the account; attempts and refunds already on it still complete. */
    public AccountView disable(String merchantId, String providerCode, String actor) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            ProviderAccountRow account = repository.findProviderAccount(merchantId, providerCode)
                    .orElseThrow(() -> GatewayException.notFound("Provider account", merchantId + "/" + providerCode));
            if (ACTIVE.equals(account.status())) {
                repository.updateProviderAccount(account.id(), DISABLED, account.credentialsEncrypted(),
                        account.credentialsUpdatedAt(), now);
                audit.record("ADMIN", actor, "provider_account.disabled", "provider_account", account.id(),
                        Map.of("merchant_id", merchantId, "provider", providerCode));
            }
            return view(repository.findProviderAccountById(account.id()).orElseThrow());
        });
    }

    /** Providers that cannot be linked without credentials. */
    public boolean requiresCredentials(String providerCode) {
        return providers.find(providerCode).stream().flatMap(p -> p.credentialFields().stream()).anyMatch(CredentialField::required);
    }

    @Override
    public MerchantAccount require(String merchantId, String providerCode) {
        return repository.findProviderAccount(merchantId, providerCode).map(this::toAccount)
                .orElseThrow(() -> new IllegalStateException("Merchant " + merchantId + " has no " + providerCode + " account"));
    }

    @Override
    public Optional<MerchantAccount> findById(String accountId) {
        return repository.findProviderAccountById(accountId).map(this::toAccount);
    }

    private static void validate(PaymentProvider provider, Map<String, String> credentials) {
        Map<String, CredentialField> declared = new LinkedHashMap<>();
        provider.credentialFields().forEach(field -> declared.put(field.name(), field));
        credentials.forEach((name, value) -> {
            if (!declared.containsKey(name)) {
                throw GatewayException.validation("credentials." + name, "is not a credential of " + provider.code()
                        + (declared.isEmpty() ? "" : " (expected: " + String.join(", ", declared.keySet()) + ")"));
            }
            if (value == null || value.isBlank() || value.length() > MAX_CREDENTIAL_LENGTH) {
                throw GatewayException.validation("credentials." + name, "must be 1-" + MAX_CREDENTIAL_LENGTH + " characters");
            }
        });
    }

    private static List<String> missingRequired(PaymentProvider provider, Map<String, String> stored) {
        List<String> missing = new ArrayList<>();
        provider.credentialFields().stream()
                .filter(field -> field.required() && !stored.containsKey(field.name()))
                .forEach(field -> missing.add(field.name()));
        return missing;
    }

    private MerchantAccount toAccount(ProviderAccountRow row) {
        Map<String, String> credentials = row.credentialsEncrypted() == null ? Map.of()
                : decrypt(row.id(), row.credentialsEncrypted());
        return new MerchantAccount(row.id(), row.merchantId(), row.providerCode(), credentials);
    }

    private AccountView view(ProviderAccountRow row) {
        Map<String, Boolean> secret = new LinkedHashMap<>();
        providers.find(row.providerCode()).ifPresent(p -> p.credentialFields().forEach(f -> secret.put(f.name(), f.secret())));
        Map<String, String> shown = new TreeMap<>();
        if (row.credentialsEncrypted() != null) {
            decrypt(row.id(), row.credentialsEncrypted()).forEach((name, value) ->
                    shown.put(name, secret.getOrDefault(name, true) ? mask(value) : value));
        }
        return new AccountView(row.id(), row.providerCode(), row.status(), shown, row.credentialsUpdatedAt(),
                row.createdAt(), row.disabledAt());
    }

    private Map<String, String> decrypt(String accountId, byte[] encrypted) {
        return json.read(cipher.decrypt(encrypted, context(accountId)), CREDENTIALS);
    }

    private static String mask(String value) {
        return value.length() >= 12 ? "\u2026" + value.substring(value.length() - 4) : "\u2026";
    }

    static String context(String accountId) {
        return "merchant_provider_account:" + accountId;
    }
}
