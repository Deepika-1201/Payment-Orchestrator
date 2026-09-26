package com.payments.gateway.ledger;

import com.payments.gateway.ledger.LedgerAdjustmentRepository.AdjustmentRow;
import com.payments.gateway.ledger.LedgerModel.Leg;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.ErrorCode;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.model.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual corrections to the shadow ledger with maker-checker (ADR-024): one operator proposes, a different one
 * approves, and only the approval posts. Postings are ordinary balanced ledger transactions, so a mistake is
 * corrected by another adjustment, never by editing history.
 */
@Service
public class LedgerAdjustmentService {

    static final Duration APPROVAL_WINDOW = Duration.ofDays(7);

    public record AdjustmentRequest(String merchantId, String provider, LedgerAccountType debitAccount,
                                    LedgerAccountType creditAccount, Money amount, String reason, String reference) {
    }

    public record AdjustmentView(String id, String merchantId, String provider, String debitAccount,
                                 String creditAccount, long amount, String currency, String reason, String reference,
                                 String status, String requestedBy, Instant requestedAt, Instant expiresAt,
                                 String decidedBy, Instant decidedAt, String decisionNote) {
    }

    private final LedgerAdjustmentRepository repository;
    private final LedgerService ledger;
    private final MerchantDirectory merchants;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LedgerAdjustmentService(LedgerAdjustmentRepository repository, LedgerService ledger, MerchantDirectory merchants,
                                   AuditLogger audit, TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.ledger = ledger;
        this.merchants = merchants;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public AdjustmentView request(AdjustmentRequest request, String actor) {
        merchants.require(request.merchantId());
        if (!merchants.hasProviderAccount(request.merchantId(), request.provider())) {
            throw GatewayException.validation("provider", "merchant has no " + request.provider() + " account");
        }
        if (request.debitAccount() == request.creditAccount()) {
            throw GatewayException.validation("credit_account", "must differ from debit_account");
        }
        Instant now = clock.instant();
        AdjustmentRow row = new AdjustmentRow(Ids.newId("ladj"), request.merchantId(), request.provider(),
                request.debitAccount(), request.creditAccount(), request.amount().amount(), request.amount().currency(),
                request.reason(), request.reference(), "PENDING", actor, now, now.plus(APPROVAL_WINDOW), null, null, null);
        tx.executeWithoutResult(status -> {
            repository.insert(row);
            audit.record("ADMIN", actor, "ledger_adjustment.requested", "ledger_adjustment", row.id(), details(row));
        });
        return get(row.id());
    }

    /** Posts the adjustment; the approver must be a different operator than the requester. */
    public AdjustmentView approve(String id, String note, String actor) {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            AdjustmentRow row = pending(id, now);
            if (row.requestedBy().equals(actor)) {
                throw new GatewayException(ErrorCode.FORBIDDEN, "An adjustment must be approved by a different operator than "
                        + "the one who requested it");
            }
            Money amount = Money.of(row.amount(), row.currency());
            ledger.post(new Posting(row.merchantId(), row.providerCode(), LedgerTransactionType.ADJUSTMENT, "ADJUSTMENT",
                    row.id(), row.reason(), now, List.of(Leg.debit(row.debitAccount(), amount),
                    Leg.credit(row.creditAccount(), amount))));
            repository.decide(id, "APPROVED", actor, note, now);
            audit.record("ADMIN", actor, "ledger_adjustment.approved", "ledger_adjustment", id, details(row));
        });
        return get(id);
    }

    /** Declines (or, by the requester, withdraws) a pending adjustment; nothing is posted. */
    public AdjustmentView reject(String id, String note, String actor) {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            AdjustmentRow row = pending(id, now);
            repository.decide(id, "REJECTED", actor, note, now);
            audit.record("ADMIN", actor, "ledger_adjustment.rejected", "ledger_adjustment", id, details(row));
        });
        return get(id);
    }

    public AdjustmentView get(String id) {
        return repository.find(id).map(this::view).orElseThrow(() -> GatewayException.notFound("Ledger adjustment", id));
    }

    public List<AdjustmentView> list(String status, String merchantId) {
        String normalized = status == null ? null : status.toUpperCase(Locale.ROOT);
        return repository.list(normalized, merchantId, 500).stream().map(this::view).toList();
    }

    private AdjustmentRow pending(String id, Instant now) {
        AdjustmentRow row = repository.lock(id).orElseThrow(() -> GatewayException.notFound("Ledger adjustment", id));
        if (!"PENDING".equals(row.status())) {
            throw GatewayException.invalidState("Adjustment " + id + " is already " + row.status().toLowerCase(Locale.ROOT));
        }
        if (!now.isBefore(row.expiresAt())) {
            throw GatewayException.invalidState("Adjustment " + id + " expired unapproved; request it again");
        }
        return row;
    }

    private AdjustmentView view(AdjustmentRow row) {
        String status = "PENDING".equals(row.status()) && !clock.instant().isBefore(row.expiresAt()) ? "expired"
                : row.status().toLowerCase(Locale.ROOT);
        return new AdjustmentView(row.id(), row.merchantId(), row.providerCode(),
                row.debitAccount().name().toLowerCase(Locale.ROOT), row.creditAccount().name().toLowerCase(Locale.ROOT),
                row.amount(), row.currency(), row.reason(), row.reference(), status, row.requestedBy(), row.requestedAt(),
                row.expiresAt(), row.decidedBy(), row.decidedAt(), row.decisionNote());
    }

    private static Map<String, Object> details(AdjustmentRow row) {
        Map<String, Object> details = new HashMap<>();
        details.put("merchant_id", row.merchantId());
        details.put("provider", row.providerCode());
        details.put("debit", row.debitAccount().name());
        details.put("credit", row.creditAccount().name());
        details.put("amount", row.amount());
        details.put("currency", row.currency());
        details.put("reference", row.reference());
        return details;
    }
}
