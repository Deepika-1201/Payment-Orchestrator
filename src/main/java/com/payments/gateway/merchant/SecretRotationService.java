package com.payments.gateway.merchant;

import com.payments.gateway.merchant.MerchantRepository.StoredCiphertext;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.crypto.KeyRingCiphertexts;
import com.payments.gateway.shared.crypto.SecretCipher;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Moves every stored secret to the primary data key (ADR-025): merchant webhook secrets, PSP credentials and the
 * other modules' ciphertexts (such as dispute evidence keys), keeping each ciphertext's authenticated context.
 * Idempotent and safe to rerun; a row changed concurrently keeps its newer value (compare-and-swap) and is picked up by
 * the next run.
 */
@Service
public class SecretRotationService {

    private static final Logger log = LoggerFactory.getLogger(SecretRotationService.class);

    /** Stored ciphertexts per data key id; rotation is complete when only the primary key remains. */
    public record KeyUsage(String primaryKeyId, Map<String, Integer> ciphertextsByKey) {

        public boolean complete() {
            return ciphertextsByKey.keySet().stream().allMatch(primaryKeyId::equals);
        }
    }

    public record RotationResult(int reEncrypted, KeyUsage usage) {
    }

    private final MerchantRepository repository;
    private final List<KeyRingCiphertexts> others;
    private final SecretCipher cipher;
    private final AuditLogger audit;

    public SecretRotationService(MerchantRepository repository, List<KeyRingCiphertexts> others, SecretCipher cipher,
                                 AuditLogger audit) {
        this.repository = repository;
        this.others = others;
        this.cipher = cipher;
        this.audit = audit;
    }

    public KeyUsage usage() {
        Map<String, Integer> byKey = new TreeMap<>();
        for (StoredCiphertext row : repository.findWebhookSecretCiphertexts()) {
            count(byKey, row.first());
            count(byKey, row.second());
        }
        repository.findCredentialCiphertexts().forEach(row -> count(byKey, row.first()));
        others.forEach(source -> source.keyRingCiphertexts().forEach(row -> count(byKey, row.ciphertext())));
        return new KeyUsage(cipher.primaryKeyId(), byKey);
    }

    public RotationResult reEncryptAll(String actor) {
        int changed = 0;
        for (StoredCiphertext row : repository.findWebhookSecretCiphertexts()) {
            if (stale(row.first()) || stale(row.second())) {
                byte[] current = reEncrypt(row.first(), null);
                byte[] previous = reEncrypt(row.second(), null);
                if (repository.swapWebhookSecretCiphertexts(row.rowId(), row, current, previous)) {
                    changed++;
                }
            }
        }
        for (StoredCiphertext row : repository.findCredentialCiphertexts()) {
            if (stale(row.first()) && repository.swapCredentialCiphertext(row.rowId(), row.first(),
                    reEncrypt(row.first(), ProviderAccountService.context(row.rowId())))) {
                changed++;
            }
        }
        for (KeyRingCiphertexts source : others) {
            for (KeyRingCiphertexts.Stored row : source.keyRingCiphertexts()) {
                if (stale(row.ciphertext()) && source.swapKeyRingCiphertext(row.rowId(), row.ciphertext(),
                        reEncrypt(row.ciphertext(), row.context()))) {
                    changed++;
                }
            }
        }
        KeyUsage usage = usage();
        audit.record("ADMIN", actor, "data_keys.re_encrypted", "data_key", cipher.primaryKeyId(),
                Map.of("rows_re_encrypted", changed, "ciphertexts_by_key", usage.ciphertextsByKey()));
        log.info("Re-encrypted {} rows under data key {}; remaining usage {}", changed, cipher.primaryKeyId(),
                usage.ciphertextsByKey());
        return new RotationResult(changed, usage);
    }

    private boolean stale(byte[] payload) {
        return payload != null && !cipher.isUnderPrimaryKey(payload);
    }

    private byte[] reEncrypt(byte[] payload, String context) {
        if (payload == null || cipher.isUnderPrimaryKey(payload)) {
            return payload;
        }
        return cipher.encrypt(cipher.decrypt(payload, context), context);
    }

    private static void count(Map<String, Integer> byKey, byte[] payload) {
        if (payload != null) {
            byKey.merge(SecretCipher.keyId(payload).orElse("unreadable"), 1, Integer::sum);
        }
    }
}
