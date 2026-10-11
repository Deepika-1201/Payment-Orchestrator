package com.payments.gateway.payout.spi;

import com.payments.gateway.shared.model.Money;
import java.util.Map;

public interface PayoutProvider {
    record Destination(String beneficiary, String accountNumber, String ifsc) {
    }

    record Instruction(String id, String merchantId, String sourceAccount, Money amount, Destination destination) {
    }

    enum Outcome {
        PENDING, SUCCEEDED, REJECTED, RETURNED, UNKNOWN, NOT_FOUND, FAILURE_UNCONFIRMED
    }

    record Result(Outcome outcome, String providerReference, String referenceId, Money amount, Destination destination,
                  String bankReference, String reason, String destinationReference) {
        public Result(Outcome outcome, String providerReference, String referenceId, Money amount, Destination destination,
                      String bankReference, String reason) {
            this(outcome, providerReference, referenceId, amount, destination, bankReference, reason, null);
        }

        public static Result unknown(String reason) {
            return new Result(Outcome.UNKNOWN, null, null, null, null, null, reason);
        }
    }

    record Event(String id, String referenceId, Result result) {
    }

    class InvalidWebhook extends RuntimeException {
        public InvalidWebhook(String message) {
            super(message);
        }
    }

    String code();

    String sourceAccount();

    void validate(Instruction instruction);

    Result create(Instruction instruction);

    Result fetch(Instruction instruction, String providerReference);

    default Result resolveDestination(Result result) {
        return result;
    }

    Event parseWebhook(Map<String, String> headers, String body);
}