package com.payments.gateway.treasury;

import com.payments.gateway.ledger.EntryDirection;
import com.payments.gateway.ledger.LedgerAccountType;
import com.payments.gateway.ledger.LedgerModel.Leg;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.ledger.LedgerTransactionType;
import com.payments.gateway.merchant.MerchantDirectory;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.audit.AuditLogger;
import com.payments.gateway.shared.error.GatewayException;
import com.payments.gateway.shared.events.CurrencyConversion;
import com.payments.gateway.shared.events.BillingDocumentIssued;
import com.payments.gateway.shared.events.PayoutFundsMovement;
import com.payments.gateway.shared.events.FundsMovement;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.treasury.TreasuryAmounts.Kind;
import com.payments.gateway.treasury.TreasuryModel.Balance;
import com.payments.gateway.treasury.TreasuryModel.PspFee;
import com.payments.gateway.treasury.TreasuryModel.Receipt;
import com.payments.gateway.treasury.TreasuryRepository.StoredMovement;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TreasuryService {
    public static final String BOOK = "AGGREGATOR";

    private final TreasuryRepository repository;
    private final LedgerService ledger;
    private final MerchantDirectory merchants;
    private final SettlementCalendar calendar;
    private final Clock clock;
    private final AuditLogger audit;

    public TreasuryService(TreasuryRepository repository, LedgerService ledger, MerchantDirectory merchants,
                           SettlementCalendar calendar, Clock clock, AuditLogger audit) {
        this.repository = repository;
        this.ledger = ledger;
        this.merchants = merchants;
        this.calendar = calendar;
        this.clock = clock;
        this.audit = audit;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(FundsMovement movement) {
        if (!merchants.escrowControlled(movement.merchantId())) {
            return;
        }
        repository.lock();
        int days = repository.policy(movement.merchantId()).map(TreasuryModel.Policy::businessDays).orElse(1);
        Instant eligible = movement.type() == FundsMovement.Type.CAPTURE || movement.type() == FundsMovement.Type.CREDIT_APPLIED
                ? calendar.eligibleAt(movement.occurredAt(), days) : movement.occurredAt();
        if (!repository.movement(movement, eligible)) {
            if (!repository.movement(movement.type().name(), movement.referenceId()).orElseThrow().movement().equals(movement)) {
                throw new IllegalStateException("Conflicting treasury movement reference");
            }
            return;
        }
        StoredMovement stored = new StoredMovement(movement, eligible);
        if (movement.amount().inSettlementCurrency()) {
            postMovement(stored, movement.amount(), "TREASURY", movement.type().name() + ":" + movement.referenceId(), true);
        } else {
            postMovement(stored, movement.amount(), "TREASURY_NOMINAL", movement.type().name() + ":" + movement.referenceId(), false);
            repository.conversion(movement.type().name(), movement.referenceId()).ifPresent(conversion -> applyConversion(stored, conversion));
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CurrencyConversion conversion) {
        if (!merchants.escrowControlled(conversion.merchantId())) {
            return;
        }
        if (conversion.referenceId() == null || !conversion.settled().inSettlementCurrency()) {
            throw new IllegalArgumentException("Treasury conversion needs a source reference and INR amount");
        }
        repository.lock();
        if (!repository.conversion(conversion)) {
            CurrencyConversion stored = repository.conversion(conversion.kind().name(), conversion.referenceId()).orElseThrow();
            if (!stored.merchantId().equals(conversion.merchantId()) || !stored.providerCode().equals(conversion.providerCode())
                    || !stored.settled().equals(conversion.settled())) {
                throw new IllegalStateException("Conflicting treasury conversion reference");
            }
            return;
        }
        repository.movement(conversion.kind().name(), conversion.referenceId()).ifPresent(movement -> applyConversion(movement, conversion));
    }

    public Balance balance(String merchantId) {
        if (merchantId != null) {
            merchants.require(merchantId);
        }
        return repository.balance(merchantId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        repository.lock();
    }

    @Transactional
    public void raiseException(String merchantId, String type, String reference, String details) {
        repository.openException(Ids.newId("tex"), merchantId, type, reference, details, clock.instant());
    }

    public boolean hasOpenException(String merchantId) {
        return repository.hasOpenException(merchantId);
    }

    public List<TreasuryModel.ExceptionView> exceptions(String merchantId, String status) {
        String normalized = status == null ? null : status.toUpperCase(java.util.Locale.ROOT);
        if (normalized != null && !List.of("OPEN", "RESOLVED").contains(normalized)) {
            throw GatewayException.validation("status", "must be open or resolved");
        }
        return repository.exceptions(merchantId, normalized);
    }

    @Transactional
    public TreasuryModel.ExceptionView resolveException(String id, String actor, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw GatewayException.validation("reason", "must contain 1-1000 characters");
        }
        repository.lock();
        repository.exception(id).orElseThrow(() -> GatewayException.notFound("Treasury exception", id));
        if (repository.resolveException(id, actor, reason, clock.instant())) {
            audit.record("ADMIN", actor, "treasury_exception.resolved", "treasury_exception", id, Map.of("reason", reason));
        }
        return repository.exception(id).orElseThrow();
    }

    public long countExceptions(String type) {
        return repository.countExceptions(type);
    }

    public Map<String, Long> negativeMerchants() {
        Map<String, Long> negative = new HashMap<>();
        for (var balance : ledger.bookBalances(BOOK, null)) {
            if (balance.account() == LedgerAccountType.MERCHANT_PAYABLE && "INR".equals(balance.currency()) && balance.balance() < 0) {
                negative.put(balance.merchantId(), balance.balance());
            }
        }
        return Map.copyOf(negative);
    }

    public boolean bankMismatch() {
        return repository.bankMismatch();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bankResult(Instant cutoff, boolean matched, Map<String, Long> holds) {
        repository.bankResult(cutoff, matched, holds);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(PayoutFundsMovement movement) {
        repository.lock();
        if (!movement.amount().inSettlementCurrency() || movement.amount().amount() <= 0) {
            throw new IllegalArgumentException("Payout postings require a positive INR amount");
        }
        LedgerTransactionType type = switch (movement.type()) {
            case PAID -> LedgerTransactionType.PAYOUT_SENT;
            case RELEASED -> LedgerTransactionType.SETTLEMENT_RELEASED;
            case RETURNED -> LedgerTransactionType.PAYOUT_RETURNED;
        };
        List<Leg> legs = switch (movement.type()) {
            case PAID -> List.of(Leg.debit(LedgerAccountType.SETTLEMENT_PAYABLE, movement.amount()),
                    Leg.credit(LedgerAccountType.ESCROW, movement.amount()));
            case RELEASED -> List.of(Leg.debit(LedgerAccountType.SETTLEMENT_PAYABLE, movement.amount()),
                    Leg.credit(LedgerAccountType.MERCHANT_PAYABLE, movement.amount()));
            case RETURNED -> List.of(Leg.debit(LedgerAccountType.ESCROW, movement.amount()),
                    Leg.credit(LedgerAccountType.MERCHANT_PAYABLE, movement.amount()));
        };
        if (ledger.post(new Posting(movement.merchantId(), BOOK, type, "PAYOUT", movement.payoutId(),
                "Payout " + movement.type(), movement.occurredAt(), legs)) && movement.type() != PayoutFundsMovement.Type.PAID) {
            repository.source(movement.merchantId(), BOOK, Kind.PAYOUT_RELEASED, movement.payoutId(), movement.amount().amount(),
                    0, movement.occurredAt(), movement.occurredAt());
            recoverReserve(movement.merchantId(), movement.occurredAt());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recoverReserve(String merchantId, Instant now) {
        repository.lock();
        Balance balance = repository.balance(merchantId);
        if (balance.merchantPayable() < 0 && balance.reserve() > 0) {
            moveReserve(merchantId, Math.min(Math.negateExact(balance.merchantPayable()), balance.reserve()), false, "debt-recovery", now);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void moveReserve(String merchantId, long amount, boolean hold, String reason, Instant now) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Reserve movements must be positive");
        }
        repository.lock();
        Balance balance = repository.balance(merchantId);
        if (amount > (hold ? balance.merchantPayable() : balance.reserve())) {
            throw new IllegalArgumentException("Reserve movement exceeds its available balance");
        }
        String reference = Ids.newId("rsv");
        Money money = Money.of(amount, "INR");
        ledger.post(new Posting(merchantId, BOOK, hold ? LedgerTransactionType.RESERVE_HELD : LedgerTransactionType.RESERVE_RELEASED,
                "TREASURY_RESERVE", reference, reason, now, hold
                    ? List.of(Leg.debit(LedgerAccountType.MERCHANT_PAYABLE, money), Leg.credit(LedgerAccountType.RESERVE, money))
                    : List.of(Leg.debit(LedgerAccountType.RESERVE, money), Leg.credit(LedgerAccountType.MERCHANT_PAYABLE, money))));
        repository.source(merchantId, BOOK, hold ? Kind.RESERVE_HELD : Kind.RESERVE_RELEASED, reference, amount, 0, now, now);
    }

    @Transactional
    public void pspFee(String merchantId, String provider, String reference, Money amount, long merchantAmount, Instant occurredAt) {
        if (!merchants.escrowControlled(merchantId) || !amount.inSettlementCurrency() || amount.amount() <= 0
                || merchantAmount < 0 || merchantAmount > amount.amount()) {
            throw new IllegalArgumentException("Invalid authoritative PSP fee");
        }
        repository.lock();
        var existing = repository.fee(reference);
        if (existing.isPresent()) {
            PspFee fee = existing.get();
            if (!fee.merchantId().equals(merchantId) || !fee.providerCode().equals(provider) || fee.amount() != amount.amount()
                    || fee.merchantAmount() != merchantAmount) {
                throw new IllegalStateException("Conflicting PSP fee reference");
            }
            return;
        }
        repository.fee(new PspFee(reference, merchantId, provider, amount.amount(), merchantAmount, occurredAt));
        List<Leg> legs = new ArrayList<>();
        if (merchantAmount > 0) {
            legs.add(Leg.debit(LedgerAccountType.MERCHANT_PAYABLE, Money.of(merchantAmount, "INR")));
        }
        if (merchantAmount < amount.amount()) {
            legs.add(Leg.debit(LedgerAccountType.PLATFORM_FEE_INCOME, Money.of(amount.amount() - merchantAmount, "INR")));
        }
        legs.add(Leg.credit(LedgerAccountType.PSP_RECEIVABLE, amount));
        ledger.post(new Posting(merchantId, BOOK, LedgerTransactionType.PSP_FEE, "TREASURY_FEE", reference,
                "Actual withholding at " + provider, occurredAt, legs));
        if (merchantAmount > 0) {
            repository.source(merchantId, provider, Kind.PSP_FEE, reference, merchantAmount, 0, occurredAt, occurredAt);
        }
        recoverReserve(merchantId, occurredAt);
    }

    @Transactional
    public void receive(String ownerId, String provider, String settlementId, long net, String currency, String bankReference,
                        Instant settledAt, Map<String, Long> allocations) {
        if (!"INR".equals(currency) || allocations.isEmpty() || settledAt == null || settledAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("Escrow requires a completed INR settlement with matched allocations");
        }
        long total = 0;
        for (Map.Entry<String, Long> allocation : allocations.entrySet()) {
            if (!merchants.escrowControlled(allocation.getKey())
                    || !merchants.acceptsProviderScope(allocation.getKey(), provider, ownerId)) {
                throw new IllegalArgumentException("Escrow allocation is outside the collection owner's scope");
            }
            total = Math.addExact(total, allocation.getValue());
        }
        if (total != net) {
            throw new IllegalArgumentException("Escrow allocations do not match the completed PSP payout");
        }
        repository.lock();
        var existing = repository.receipt(ownerId, provider, settlementId);
        if (existing.isPresent()) {
            if (existing.get().amount() != total || !existing.get().allocations().equals(allocations)) {
                throw new IllegalStateException("Conflicting escrow receipt reference");
            }
            return;
        }
        Receipt receipt = new Receipt(Ids.newId("erc"), ownerId, provider, settlementId, total, bankReference, settledAt, allocations);
        repository.receipt(receipt);
        allocations.forEach((merchant, amount) -> {
            if (amount != 0) {
                Money absolute = Money.of(Math.abs(amount), "INR");
                List<Leg> legs = amount > 0
                        ? List.of(Leg.debit(LedgerAccountType.ESCROW, absolute), Leg.credit(LedgerAccountType.PSP_RECEIVABLE, absolute))
                        : List.of(Leg.debit(LedgerAccountType.PSP_RECEIVABLE, absolute), Leg.credit(LedgerAccountType.ESCROW, absolute));
                ledger.post(new Posting(merchant, BOOK, LedgerTransactionType.SETTLEMENT, "ESCROW_RECEIPT", receipt.id() + ":" + merchant,
                        "Completed PSP settlement " + settlementId, settledAt, legs));
            }
        });
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(BillingDocumentIssued document) {
        long total = Math.addExact(document.subtotal(), document.tax());
        if (!document.settlementDeduction() || total == 0) {
            return;
        }
        if (!merchants.isAggregator(document.merchantId())) {
            throw new IllegalStateException("Settlement deduction requires an aggregator merchant");
        }
        repository.lock();
        List<Leg> legs = new ArrayList<>();
        EntryDirection payable = document.credit() ? EntryDirection.CREDIT : EntryDirection.DEBIT;
        EntryDirection income = document.credit() ? EntryDirection.DEBIT : EntryDirection.CREDIT;
        legs.add(new Leg(LedgerAccountType.MERCHANT_PAYABLE, payable, Money.of(total, "INR")));
        if (document.subtotal() > 0) {
            legs.add(new Leg(LedgerAccountType.PLATFORM_FEE_INCOME, income, Money.of(document.subtotal(), "INR")));
        }
        if (document.tax() > 0) {
            legs.add(new Leg(LedgerAccountType.GST_PAYABLE, income, Money.of(document.tax(), "INR")));
        }
        if (ledger.post(new Posting(document.merchantId(), BOOK,
                document.credit() ? LedgerTransactionType.BILLING_CREDIT : LedgerTransactionType.BILLING_INVOICE,
                "TREASURY_BILLING", document.id(), document.credit() ? "Invoice credit note" : "Monthly service invoice",
                document.occurredAt(), legs))) {
            repository.source(document.merchantId(), BOOK, document.credit() ? Kind.CREDIT_NOTE : Kind.INVOICE,
                    document.id(), total, document.tax(), document.occurredAt(), document.occurredAt());
                recoverReserve(document.merchantId(), document.occurredAt());
        }
    }

    private void applyConversion(StoredMovement stored, CurrencyConversion conversion) {
        FundsMovement movement = stored.movement();
        if (!movement.merchantId().equals(conversion.merchantId()) || !movement.providerCode().equals(conversion.providerCode())
                || movement.amount().inSettlementCurrency()) {
            throw new IllegalStateException("Treasury conversion does not match its foreign movement");
        }
        postMovement(stored, conversion.settled(), "TREASURY_FX", conversion.id(), true);
    }

    private void postMovement(StoredMovement stored, Money amount, String referenceType, String referenceId, boolean authoritative) {
        FundsMovement movement = stored.movement();
        LedgerAccountType receivable = authoritative ? LedgerAccountType.PSP_RECEIVABLE : LedgerAccountType.FX_CONVERSION;
        LedgerAccountType merchant = authoritative ? LedgerAccountType.MERCHANT_PAYABLE : LedgerAccountType.SALES_CLEARING;
        LedgerAccountType beneficiary = movement.merchantEntitled() ? merchant : LedgerAccountType.CUSTOMER_FUNDS;
        LedgerAccountType debit;
        LedgerAccountType credit;
        switch (movement.type()) {
            case CAPTURE, CHARGEBACK_REVERSAL -> {
                debit = receivable;
                credit = beneficiary;
            }
            case REFUND, CHARGEBACK -> {
                debit = beneficiary;
                credit = receivable;
            }
            case CREDIT_RECEIVED -> {
                debit = receivable;
                credit = LedgerAccountType.CUSTOMER_FUNDS;
            }
            case CREDIT_APPLIED -> {
                debit = LedgerAccountType.CUSTOMER_FUNDS;
                credit = merchant;
            }
            case CREDIT_RETURNED -> {
                debit = LedgerAccountType.CUSTOMER_FUNDS;
                credit = receivable;
            }
            default -> throw new IllegalStateException("Unsupported treasury movement");
        }
        LedgerTransactionType type = switch (movement.type()) {
            case CAPTURE -> LedgerTransactionType.PAYMENT_CAPTURED;
            case REFUND -> LedgerTransactionType.REFUND_SUCCEEDED;
            case CHARGEBACK -> LedgerTransactionType.CHARGEBACK;
            case CHARGEBACK_REVERSAL -> LedgerTransactionType.REVERSAL;
            case CREDIT_RECEIVED -> LedgerTransactionType.CREDIT_RECEIVED;
            case CREDIT_APPLIED -> LedgerTransactionType.CREDIT_APPLIED;
            case CREDIT_RETURNED -> LedgerTransactionType.CREDIT_RETURNED;
        };
        if (ledger.post(new Posting(movement.merchantId(), BOOK, type, referenceType, referenceId,
                movement.type() + " at " + movement.providerCode(), movement.occurredAt(),
                List.of(new Leg(debit, EntryDirection.DEBIT, amount), new Leg(credit, EntryDirection.CREDIT, amount))))
                && authoritative && (debit == merchant || credit == merchant)) {
            repository.source(movement.merchantId(), movement.providerCode(), Kind.valueOf(movement.type().name()),
                    movement.referenceId(), amount.amount(), 0, movement.occurredAt(), stored.eligibleAt());
                recoverReserve(movement.merchantId(), movement.occurredAt());
        }
    }
}