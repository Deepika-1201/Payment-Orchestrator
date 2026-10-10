package com.payments.gateway.payment.domain;

import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.Conversion;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import java.util.Objects;

/** Normalized PSP evidence to apply to an attempt. {@code reportedAmount} is verified before success is accepted. */
public record AttemptUpdate(AttemptStatus status, String providerReference, NextAction nextAction, Failure failure,
                            Money reportedAmount, CardDetails card, Conversion conversion) {

    public AttemptUpdate {
        Objects.requireNonNull(status, "status");
        if (status == AttemptStatus.FAILED) {
            Objects.requireNonNull(failure, "failure is required for FAILED");
        }
    }

    public AttemptUpdate(AttemptStatus status, String providerReference, NextAction nextAction, Failure failure,
                         Money reportedAmount) {
        this(status, providerReference, nextAction, failure, reportedAmount, null, null);
    }

    public AttemptUpdate(AttemptStatus status, String providerReference, NextAction nextAction, Failure failure,
                         Money reportedAmount, CardDetails card) {
        this(status, providerReference, nextAction, failure, reportedAmount, card, null);
    }

    public static AttemptUpdate of(AttemptStatus status) {
        return new AttemptUpdate(status, null, null, null, null);
    }

    public static AttemptUpdate failed(String code, FailureCategory category, String message) {
        return new AttemptUpdate(AttemptStatus.FAILED, null, null, new Failure(code, category, message), null);
    }
}
