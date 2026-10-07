package com.payments.gateway.provider.spi;

import com.payments.gateway.shared.model.MandateFrequency;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.Money;
import java.time.Instant;

/** Requests of the mandate operations (ADR-035). Each is idempotent at the PSP per the id named first. */
public final class MandateRequests {

    private MandateRequests() {
    }

    /**
     * {@code registrationAttemptId} and {@code registrationAmount} are set when the PSP charges for the authorization; the
     * charge is our attempt, so the PSP must echo that id with it.
     */
    public record CreateMandateRequest(String mandateId, String merchantId, MandateInstrument instrument, Money maxAmount,
                                       MandateFrequency frequency, Instant startAt, Instant endAt,
                                       Instant authorizationExpiresAt, String description, String customerName,
                                       String customerEmail, String customerPhone, String registrationAttemptId,
                                       Money registrationAmount, String returnUrl) {
    }

    /** Any reference may be null; adapters fall back to looking the registration up by {@code mandateId}. */
    public record MandateQuery(String mandateId, MandateInstrument instrument, String providerReference,
                               String providerMandateReference, String providerCustomerReference) {
    }

    /** {@code notificationId} is {@code <debit id>.<cycle>}: one notification per execution cycle. */
    public record DebitNotificationRequest(String notificationId, String debitId, String mandateId,
                                           MandateInstrument instrument, String providerMandateReference,
                                           String providerCustomerReference, Money amount, Instant debitAfter,
                                           String description) {
    }

    public record DebitNotificationQuery(String notificationId, String providerReference) {
    }

    /** {@code notificationReference} is null for instruments without pre-debit notifications (eNACH). */
    public record ExecuteDebitRequest(String attemptId, String debitId, String mandateId, MandateInstrument instrument,
                                      String providerMandateReference, String providerCustomerReference,
                                      String notificationReference, Money amount, String description,
                                      String customerEmail, String customerPhone) {
    }
}
