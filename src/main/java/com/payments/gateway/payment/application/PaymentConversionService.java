package com.payments.gateway.payment.application;

import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.infrastructure.FxConversionRepository;
import com.payments.gateway.payment.infrastructure.FxConversionRepository.Stored;
import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.events.CurrencyConversion;
import com.payments.gateway.shared.events.CurrencyConversion.Kind;
import com.payments.gateway.shared.model.Conversion;
import com.payments.gateway.shared.model.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentConversionService {

    public enum Source { PSP, SETTLEMENT_REPORT }

    public enum Result { RECORDED, UNCHANGED, AMOUNT_MISMATCH, MISSING_DEPENDENCY, NOT_APPLICABLE }

    private final FxConversionRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PaymentConversionService(FxConversionRepository repository, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    public Optional<Conversion> find(Kind kind, String referenceId) {
        return referenceId == null ? Optional.empty() : repository.find(kind, referenceId).map(Stored::conversion);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result record(Payment payment, String attemptId, Kind kind, String referenceId, Money amount,
                         Conversion conversion, Source source) {
        if (amount.inSettlementCurrency() || conversion == null) {
            return Result.NOT_APPLICABLE;
        }
        PaymentAttempt attempt = payment.attempt(attemptId).orElseThrow();
        if (attempt.status() != AttemptStatus.SUCCEEDED) {
            return Result.MISSING_DEPENDENCY;
        }
        Optional<Stored> existing = repository.find(kind, referenceId);
        if (existing.isPresent()) {
            return compare(existing.get(), payment.id(), attemptId, amount, conversion);
        }
        if (amount.amount() <= 0 || !amount.currency().equals(attempt.amount().currency())) {
            return Result.AMOUNT_MISMATCH;
        }
        long carried;
        if (kind == Kind.CAPTURE) {
            if (!referenceId.equals(attemptId) || !amount.equals(attempt.capturedAmount())) {
                return Result.AMOUNT_MISMATCH;
            }
            carried = conversion.settled().amount();
        } else {
            Optional<Stored> capture = repository.find(Kind.CAPTURE, attemptId);
            if (capture.isEmpty()) {
                return Result.MISSING_DEPENDENCY;
            }
            if (amount.amount() > capture.get().amount().amount()) {
                return Result.AMOUNT_MISMATCH;
            }
            boolean reversal = kind == Kind.CHARGEBACK_REVERSAL;
            if (reversal) {
                Optional<Stored> chargeback = repository.find(Kind.CHARGEBACK, referenceId);
                if (chargeback.isEmpty()) {
                    return Result.MISSING_DEPENDENCY;
                }
                if (!chargeback.get().attemptId().equals(attemptId) || !chargeback.get().amount().equals(amount)) {
                    return Result.AMOUNT_MISMATCH;
                }
            }
            long before = repository.netReturned(attemptId);
            long after = Math.addExact(before, reversal ? -amount.amount() : amount.amount());
            if (after < 0 || (kind == Kind.REFUND && after > capture.get().amount().amount())) {
                return Result.MISSING_DEPENDENCY;
            }
            long difference = Math.subtractExact(allocate(capture.get(), after), allocate(capture.get(), before));
            carried = reversal ? -difference : difference;
        }
        Instant now = clock.instant();
        Stored stored = new Stored(Ids.newId("fxc"), payment.id(), attemptId, kind, referenceId, amount, conversion, carried);
        if (!repository.insert(stored, payment.merchantId(), attempt.providerCode(), source.name(), now)) {
            return compare(repository.find(kind, referenceId).orElseThrow(), payment.id(), attemptId, amount, conversion);
        }
        events.publishEvent(new CurrencyConversion(stored.id(), kind, payment.merchantId(), attempt.providerCode(),
                conversion.settled(), carried, now));
        return Result.RECORDED;
    }

    private static long allocate(Stored capture, long amount) {
        return BigDecimal.valueOf(capture.conversion().settled().amount()).multiply(BigDecimal.valueOf(amount))
                .divide(BigDecimal.valueOf(capture.amount().amount()), 0, RoundingMode.HALF_EVEN).longValueExact();
    }

    private static Result compare(Stored existing, String paymentId, String attemptId, Money amount, Conversion conversion) {
        return existing.paymentId().equals(paymentId) && existing.attemptId().equals(attemptId)
                && existing.amount().equals(amount) && existing.conversion().settled().equals(conversion.settled())
                ? Result.UNCHANGED : Result.AMOUNT_MISMATCH;
    }
}