package com.payments.gateway.ledger;

import com.payments.gateway.ledger.LedgerModel.AccountBalance;
import com.payments.gateway.ledger.LedgerModel.Leg;
import com.payments.gateway.ledger.LedgerModel.Posting;
import com.payments.gateway.ledger.LedgerModel.TransactionView;
import com.payments.gateway.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Immutable double-entry shadow ledger (ADR-012). Postings join the caller's transaction, are idempotent on their
 * reference, and are validated here and again by the database at commit.
 */
@Service
public class LedgerService {

    private final LedgerRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LedgerService(LedgerRepository repository, TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.tx = tx;
        this.clock = clock;
    }

    /** Returns false if an identical posting (same reference and type) was already recorded. */
    public boolean post(Posting posting) {
        validate(posting);
        return Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = clock.instant();
            String transactionId = Ids.newId("ltx");
            if (!repository.insertTransaction(transactionId, posting, now)) {
                return false;
            }
            for (Leg leg : posting.legs()) {
                String accountId = repository.accountId(posting.merchantId(), posting.providerCode(), leg.account(),
                        leg.amount().currency(), now);
                repository.insertEntry(transactionId, accountId, leg.direction(), leg.amount(), now);
            }
            return true;
        }));
    }

    public List<AccountBalance> balances(String merchantId, String providerCode) {
        return repository.balances(merchantId, providerCode);
    }

    public long balance(String merchantId, String providerCode, LedgerAccountType account, String currency) {
        return repository.balances(merchantId, providerCode).stream()
                .filter(b -> b.account() == account && b.currency().equals(currency))
                .mapToLong(AccountBalance::balance)
                .sum();
    }

    public List<TransactionView> transactionsForReference(String referenceId) {
        return repository.transactionsForReference(referenceId);
    }

    static void validate(Posting posting) {
        if (posting.legs().size() < 2) {
            throw new IllegalArgumentException("a ledger transaction needs at least two legs");
        }
        String currency = posting.legs().getFirst().amount().currency();
        long debits = 0;
        long credits = 0;
        for (Leg leg : posting.legs()) {
            if (!leg.amount().currency().equals(currency)) {
                throw new IllegalArgumentException("all legs of a ledger transaction must share one currency");
            }
            if (leg.amount().amount() <= 0) {
                throw new IllegalArgumentException("ledger leg amounts must be positive");
            }
            if (leg.direction() == EntryDirection.DEBIT) {
                debits = Math.addExact(debits, leg.amount().amount());
            } else {
                credits = Math.addExact(credits, leg.amount().amount());
            }
        }
        if (debits != credits) {
            throw new IllegalArgumentException("unbalanced ledger transaction: debits " + debits + " != credits " + credits);
        }
    }
}
