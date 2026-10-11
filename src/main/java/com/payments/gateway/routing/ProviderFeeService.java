package com.payments.gateway.routing;

import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.provider.ProviderRegistry;
import com.payments.gateway.provider.spi.ProviderCapabilities;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.MethodType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProviderFeeService {

    public record PublishCommand(MethodType method, String currency, long fixedAmount, int rateBps, int taxBps,
                                 ProviderFeeSchedule.Collection collection, Instant effectiveAt) {
    }

    private final ProviderFeeScheduleRepository repository;
    private final MerchantDirectory merchants;
    private final ProviderRegistry providers;
    private final TransactionTemplate tx;
    private final AuditLogger audit;
    private final Clock clock;

    public ProviderFeeService(ProviderFeeScheduleRepository repository, MerchantDirectory merchants, ProviderRegistry providers,
                              TransactionTemplate tx, AuditLogger audit, Clock clock) {
        this.repository = repository;
        this.merchants = merchants;
        this.providers = providers;
        this.tx = tx;
        this.audit = audit;
        this.clock = clock;
    }

    public ProviderFeeSchedule publish(String merchantId, String providerCode, PublishCommand command, String actor) {
        requireAccount(merchantId, providerCode);
        ProviderCapabilities capabilities = providers.require(providerCode).capabilities();
        boolean methodSupported = command.method() == MethodType.MANDATE ? !capabilities.mandates().isEmpty()
                : capabilities.methods().containsKey(command.method());
        boolean currencySupported = capabilities.currencies().contains(command.currency())
                || (command.method() == MethodType.CARD && capabilities.foreignCurrencies().containsKey(command.currency()));
        if (!methodSupported || !currencySupported) {
            throw GatewayException.validation("method", "provider does not support this method and currency");
        }
        Instant now = clock.instant();
        Instant effectiveAt = command.effectiveAt() == null ? now : command.effectiveAt().truncatedTo(ChronoUnit.MICROS);
        if (effectiveAt.isBefore(now)) {
            throw GatewayException.validation("effective_at", "cannot be backdated");
        }
        ProviderFeeSchedule schedule = new ProviderFeeSchedule(Ids.newId("pfs"), merchantId, providerCode, command.method(),
                command.currency(), command.fixedAmount(), command.rateBps(), command.taxBps(), command.collection(), effectiveAt, now);
        try {
            return tx.execute(status -> {
                repository.insert(schedule);
                audit.record("ADMIN", actor, "provider_fee_schedule.published", "provider_fee_schedule", schedule.id(),
                        Map.of("merchant_id", merchantId, "provider", providerCode, "method", command.method().name(),
                                "currency", command.currency(), "effective_at", effectiveAt.toString()));
                return schedule;
            });
        } catch (DuplicateKeyException exception) {
            throw GatewayException.invalidState("A fee schedule already exists for this method, currency and effective time");
        }
    }

    public List<ProviderFeeSchedule> history(String merchantId, String providerCode) {
        requireAccount(merchantId, providerCode);
        return repository.history(merchantId, providerCode);
    }

    public Map<String, ProviderFeeSchedule> effective(String merchantId, Collection<String> providerCodes,
                                                      MethodType method, String currency, Instant at) {
        return repository.effective(merchantId, providerCodes, method, currency, at);
    }

    private void requireAccount(String merchantId, String providerCode) {
        merchants.require(merchantId);
        if (!merchants.hasProviderAccount(merchantId, providerCode)) {
            throw GatewayException.notFound("Merchant provider account", providerCode);
        }
    }
}