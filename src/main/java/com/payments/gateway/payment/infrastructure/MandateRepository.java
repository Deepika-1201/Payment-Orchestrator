package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.payment.domain.Mandate;
import com.payments.gateway.payment.domain.MandateCustomer;
import com.payments.gateway.payment.domain.MandateDebit;
import com.payments.gateway.payment.domain.MandateDebitSnapshot;
import com.payments.gateway.payment.domain.MandateDebitStatus;
import com.payments.gateway.payment.domain.MandateSnapshot;
import com.payments.gateway.payment.domain.MandateStatus;
import com.payments.gateway.payment.domain.StatusChange;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.MandateFrequency;
import com.payments.gateway.shared.model.MandateInstrument;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/** Mandates, their debits and the append-only {@code mandate_transitions} log (LLD §18). */
@Repository
public class MandateRepository {

    public record ClaimedDebit(String id, String paymentId, String mandateId) {
    }

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final String WAITING = "('SCHEDULED', 'NOTIFYING', 'READY')";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public MandateRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---------------------------------------------------------------- mandates

    public Optional<Mandate> findById(String id) {
        return loadMandate("SELECT * FROM mandates WHERE id = :id", Map.of("id", id));
    }

    public Optional<Mandate> findForMerchant(String merchantId, String id) {
        return loadMandate("SELECT * FROM mandates WHERE id = :id AND merchant_id = :merchantId",
                Map.of("id", id, "merchantId", merchantId));
    }

    public Optional<Mandate> lockById(String id) {
        return loadMandate("SELECT * FROM mandates WHERE id = :id FOR UPDATE", Map.of("id", id));
    }

    /** Shared lock for executing a debit: concurrent executions proceed, revocation waits for them (and vice versa). */
    public Optional<Mandate> lockForShare(String id) {
        return loadMandate("SELECT * FROM mandates WHERE id = :id FOR SHARE", Map.of("id", id));
    }

    /** Finds a mandate by one of the PSP's references, within one merchant when the event came from its account. */
    public Optional<String> findIdByProviderReference(String providerCode, String reference, String merchantScope) {
        return jdbc.sql("""
                SELECT id FROM mandates
                 WHERE provider_code = :provider AND (provider_reference = :reference OR provider_mandate_reference = :reference)
                   AND (CAST(:merchant AS text) IS NULL OR merchant_id = :merchant)
                 ORDER BY created_at
                 LIMIT 1
                """)
                .param("provider", providerCode)
                .param("reference", reference)
                .param("merchant", merchantScope)
                .query(String.class)
                .optional();
    }

    public void save(Mandate mandate) {
        if (!mandate.isNew() && !mandate.isDirty()) {
            return;
        }
        MandateSnapshot s = mandate.snapshot();
        Map<String, Object> params = new HashMap<>();
        params.put("id", s.id());
        params.put("status", s.status().name());
        params.put("registrationPaymentId", s.registrationPaymentId());
        params.put("providerReference", s.providerReference());
        params.put("providerMandateReference", s.providerMandateReference());
        params.put("providerCustomerReference", s.providerCustomerReference());
        params.put("nextAction", s.nextAction() == null ? null : json.write(s.nextAction()));
        params.put("nextCheckAt", Sql.ts(s.nextCheckAt()));
        params.put("checkCount", s.checkCount());
        params.put("failureCode", s.failureCode());
        params.put("failureMessage", s.failureMessage());
        params.put("activatedAt", Sql.ts(s.activatedAt()));
        params.put("updatedAt", Sql.ts(s.updatedAt()));
        if (mandate.isNew()) {
            params.put("merchantId", s.merchantId());
            params.put("providerCode", s.providerCode());
            params.put("instrument", s.instrument().name());
            params.put("maxAmount", s.maxAmount().amount());
            params.put("currency", s.maxAmount().currency());
            params.put("frequency", s.frequency().name());
            params.put("startAt", Sql.ts(s.startAt()));
            params.put("endAt", Sql.ts(s.endAt()));
            params.put("description", s.description());
            params.put("customerReference", s.customer().reference());
            params.put("customerName", s.customer().name());
            params.put("customerEmail", s.customer().email());
            params.put("customerPhone", s.customer().phone());
            params.put("metadata", json.write(s.metadata() == null ? Map.of() : s.metadata()));
            params.put("authorizationExpiresAt", Sql.ts(s.authorizationExpiresAt()));
            params.put("createdAt", Sql.ts(s.createdAt()));
            jdbc.sql("""
                    INSERT INTO mandates (id, merchant_id, provider_code, instrument, status, max_amount, currency, frequency,
                                          start_at, end_at, description, customer_reference, customer_name, customer_email,
                                          customer_phone, metadata, registration_payment_id, provider_reference,
                                          provider_mandate_reference, provider_customer_reference, next_action,
                                          authorization_expires_at, next_check_at, check_count, failure_code,
                                          failure_message, activated_at, version, created_at, updated_at)
                    VALUES (:id, :merchantId, :providerCode, :instrument, :status, :maxAmount, :currency, :frequency,
                            :startAt, :endAt, :description, :customerReference, :customerName, :customerEmail,
                            :customerPhone, CAST(:metadata AS jsonb), :registrationPaymentId, :providerReference,
                            :providerMandateReference, :providerCustomerReference, CAST(:nextAction AS jsonb),
                            :authorizationExpiresAt, :nextCheckAt, :checkCount, :failureCode, :failureMessage,
                            :activatedAt, 0, :createdAt, :updatedAt)
                    """)
                    .params(params)
                    .update();
        } else {
            params.put("version", s.version());
            int updated = jdbc.sql("""
                    UPDATE mandates
                       SET status = :status, registration_payment_id = :registrationPaymentId,
                           provider_reference = :providerReference, provider_mandate_reference = :providerMandateReference,
                           provider_customer_reference = :providerCustomerReference,
                           next_action = CAST(:nextAction AS jsonb), next_check_at = :nextCheckAt,
                           check_count = :checkCount, failure_code = :failureCode, failure_message = :failureMessage,
                           activated_at = :activatedAt, version = version + 1, updated_at = :updatedAt
                     WHERE id = :id AND version = :version
                    """)
                    .params(params)
                    .update();
            if (updated != 1) {
                throw new OptimisticLockingFailureException("mandate " + s.id() + " was modified concurrently");
            }
        }
        mandate.markPersisted();
    }

    /** Claims mandates due for a status check or expiry, with a lease so concurrent workers never share one. */
    public List<String> claimDueMandates(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                UPDATE mandates SET next_check_at = :leaseUntil
                 WHERE id IN (SELECT id FROM mandates
                               WHERE next_check_at <= :now
                               ORDER BY next_check_at
                               LIMIT :limit
                               FOR UPDATE SKIP LOCKED)
                RETURNING id
                """)
                .param("leaseUntil", Sql.ts(leaseUntil))
                .param("now", Sql.ts(now))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /** Brings a live mandate's next status check forward; takes no other lock, so call it outside transactions. */
    public void requestCheck(String mandateId, Instant at) {
        jdbc.sql("""
                UPDATE mandates SET next_check_at = :at
                 WHERE id = :id AND status IN ('ACTIVE', 'PAUSED') AND (next_check_at IS NULL OR next_check_at > :at)
                """)
                .param("id", mandateId)
                .param("at", Sql.ts(at))
                .update();
    }

    // ---------------------------------------------------------------- debits

    public Optional<MandateDebit> findDebit(String mandateId, String debitId) {
        return loadDebit("SELECT * FROM mandate_debits WHERE id = :id AND mandate_id = :mandateId",
                Map.of("id", debitId, "mandateId", mandateId));
    }

    public Optional<MandateDebit> findDebitById(String debitId) {
        return loadDebit("SELECT * FROM mandate_debits WHERE id = :id", Map.of("id", debitId));
    }

    /** Locks the debit of a payment; callers already hold that payment's lock (lock order payment → debit). */
    public Optional<MandateDebit> lockDebitByPayment(String paymentId) {
        return loadDebit("SELECT * FROM mandate_debits WHERE payment_id = :paymentId FOR UPDATE",
                Map.of("paymentId", paymentId));
    }

    public Optional<MandateDebit> findDebitByMerchantReference(String mandateId, String merchantDebitId) {
        return loadDebit("SELECT * FROM mandate_debits WHERE mandate_id = :mandateId AND merchant_debit_id = :ref",
                Map.of("mandateId", mandateId, "ref", merchantDebitId));
    }

    public List<MandateDebit> listDebits(String mandateId) {
        return jdbc.sql("SELECT * FROM mandate_debits WHERE mandate_id = :mandateId ORDER BY created_at, id")
                .param("mandateId", mandateId)
                .query(this::mapDebit)
                .list()
                .stream()
                .map(MandateDebit::rehydrate)
                .toList();
    }

    public boolean hasDebitInProgress(String mandateId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM mandate_debits
                                WHERE mandate_id = :mandateId AND status IN ('SCHEDULED', 'NOTIFYING', 'READY', 'EXECUTING'))
                """)
                .param("mandateId", mandateId)
                .query(Boolean.class)
                .single();
    }

    /** Debits that have not started executing, i.e. that a revocation or expiry cancels. */
    public List<String> waitingDebitPayments(String mandateId) {
        return jdbc.sql("SELECT payment_id FROM mandate_debits WHERE mandate_id = :mandateId AND status IN " + WAITING)
                .param("mandateId", mandateId)
                .query(String.class)
                .list();
    }

    public Optional<ClaimedDebit> findDebitByNotificationReference(String providerCode, String reference,
                                                                    String merchantScope) {
        return jdbc.sql("""
                SELECT d.id, d.payment_id, d.mandate_id FROM mandate_debits d JOIN mandates m ON m.id = d.mandate_id
                 WHERE d.notification_reference = :reference AND m.provider_code = :provider
                   AND (CAST(:merchant AS text) IS NULL OR d.merchant_id = :merchant)
                """)
                .param("reference", reference)
                .param("provider", providerCode)
                .param("merchant", merchantScope)
                .query((rs, n) -> new ClaimedDebit(rs.getString("id"), rs.getString("payment_id"), rs.getString("mandate_id")))
                .optional();
    }

    public List<ClaimedDebit> claimDueDebits(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                UPDATE mandate_debits SET next_action_at = :leaseUntil
                 WHERE id IN (SELECT id FROM mandate_debits
                               WHERE next_action_at <= :now
                               ORDER BY next_action_at
                               LIMIT :limit
                               FOR UPDATE SKIP LOCKED)
                RETURNING id, payment_id, mandate_id
                """)
                .param("leaseUntil", Sql.ts(leaseUntil))
                .param("now", Sql.ts(now))
                .param("limit", limit)
                .query((rs, n) -> new ClaimedDebit(rs.getString("id"), rs.getString("payment_id"), rs.getString("mandate_id")))
                .list();
    }

    public void save(MandateDebit debit) {
        if (!debit.isNew() && !debit.isDirty()) {
            return;
        }
        MandateDebitSnapshot s = debit.snapshot();
        Map<String, Object> params = new HashMap<>();
        params.put("id", s.id());
        params.put("status", s.status().name());
        params.put("notBefore", Sql.ts(s.notBefore()));
        params.put("cycle", s.cycle());
        params.put("notificationReference", s.notificationReference());
        params.put("notificationRequestedAt", Sql.ts(s.notificationRequestedAt()));
        params.put("notifiedAt", Sql.ts(s.notifiedAt()));
        params.put("nextActionAt", Sql.ts(s.nextActionAt()));
        params.put("checkCount", s.checkCount());
        params.put("lastExecutedAt", Sql.ts(s.lastExecutedAt()));
        params.put("failureCode", s.failureCode());
        params.put("failureMessage", s.failureMessage());
        params.put("updatedAt", Sql.ts(s.updatedAt()));
        if (debit.isNew()) {
            params.put("mandateId", s.mandateId());
            params.put("merchantId", s.merchantId());
            params.put("paymentId", s.paymentId());
            params.put("merchantDebitId", s.merchantDebitId());
            params.put("amount", s.amount().amount());
            params.put("currency", s.amount().currency());
            params.put("maxAmount", s.maxAmount());
            params.put("frictionlessLimit", s.frictionlessLimit());
            params.put("requiresNotification", s.requiresNotification());
            params.put("description", s.description());
            params.put("dueAt", Sql.ts(s.dueAt()));
            params.put("createdAt", Sql.ts(s.createdAt()));
            jdbc.sql("""
                    INSERT INTO mandate_debits (id, mandate_id, merchant_id, payment_id, merchant_debit_id, amount, currency,
                                                max_amount, frictionless_limit, requires_notification, status, description,
                                                due_at, not_before, cycle, notification_reference,
                                                notification_requested_at, notified_at, next_action_at, check_count,
                                                last_executed_at, failure_code, failure_message, version, created_at,
                                                updated_at)
                    VALUES (:id, :mandateId, :merchantId, :paymentId, :merchantDebitId, :amount, :currency, :maxAmount,
                            :frictionlessLimit, :requiresNotification, :status, :description, :dueAt, :notBefore, :cycle,
                            :notificationReference, :notificationRequestedAt, :notifiedAt, :nextActionAt, :checkCount,
                            :lastExecutedAt, :failureCode, :failureMessage, 0, :createdAt, :updatedAt)
                    """)
                    .params(params)
                    .update();
        } else {
            params.put("version", s.version());
            int updated = jdbc.sql("""
                    UPDATE mandate_debits
                       SET status = :status, not_before = :notBefore, cycle = :cycle,
                           notification_reference = :notificationReference,
                           notification_requested_at = :notificationRequestedAt, notified_at = :notifiedAt,
                           next_action_at = :nextActionAt, check_count = :checkCount, last_executed_at = :lastExecutedAt,
                           failure_code = :failureCode, failure_message = :failureMessage, version = version + 1,
                           updated_at = :updatedAt
                     WHERE id = :id AND version = :version
                    """)
                    .params(params)
                    .update();
            if (updated != 1) {
                throw new OptimisticLockingFailureException("debit " + s.id() + " was modified concurrently");
            }
        }
        debit.markPersisted();
    }

    // ---------------------------------------------------------------- transitions

    public void appendTransitions(String mandateId, String merchantId, List<StatusChange> changes) {
        for (StatusChange change : changes) {
            jdbc.sql("""
                    INSERT INTO mandate_transitions (mandate_id, merchant_id, entity, entity_id, from_status, to_status,
                                                     source, reason, occurred_at)
                    VALUES (:mandateId, :merchantId, :entity, :entityId, :from, :to, :source, :reason, :occurredAt)
                    """)
                    .param("mandateId", mandateId)
                    .param("merchantId", merchantId)
                    .param("entity", change.entity().name())
                    .param("entityId", change.entityId())
                    .param("from", change.fromStatus())
                    .param("to", change.toStatus())
                    .param("source", change.source().name())
                    .param("reason", change.reason())
                    .param("occurredAt", Sql.ts(change.occurredAt()))
                    .update();
        }
    }

    // ---------------------------------------------------------------- mapping

    private Optional<Mandate> loadMandate(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query(this::mapMandate).optional().map(Mandate::rehydrate);
    }

    private Optional<MandateDebit> loadDebit(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query(this::mapDebit).optional().map(MandateDebit::rehydrate);
    }

    private MandateSnapshot mapMandate(ResultSet rs, int rowNum) throws SQLException {
        String nextAction = rs.getString("next_action");
        return new MandateSnapshot(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("provider_code"),
                MandateInstrument.valueOf(rs.getString("instrument")),
                MandateStatus.valueOf(rs.getString("status")),
                Money.of(rs.getLong("max_amount"), rs.getString("currency")),
                MandateFrequency.valueOf(rs.getString("frequency")),
                Sql.instant(rs, "start_at"),
                Sql.instant(rs, "end_at"),
                rs.getString("description"),
                new MandateCustomer(rs.getString("customer_reference"), rs.getString("customer_name"),
                        rs.getString("customer_email"), rs.getString("customer_phone")),
                json.read(rs.getString("metadata"), STRING_MAP),
                rs.getString("registration_payment_id"),
                rs.getString("provider_reference"),
                rs.getString("provider_mandate_reference"),
                rs.getString("provider_customer_reference"),
                nextAction == null ? null : json.read(nextAction, NextAction.class),
                Sql.instant(rs, "authorization_expires_at"),
                Sql.instant(rs, "next_check_at"),
                rs.getInt("check_count"),
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                Sql.instant(rs, "activated_at"),
                rs.getLong("version"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"));
    }

    private MandateDebitSnapshot mapDebit(ResultSet rs, int rowNum) throws SQLException {
        return new MandateDebitSnapshot(
                rs.getString("id"),
                rs.getString("mandate_id"),
                rs.getString("merchant_id"),
                rs.getString("payment_id"),
                rs.getString("merchant_debit_id"),
                Money.of(rs.getLong("amount"), rs.getString("currency")),
                rs.getLong("max_amount"),
                Sql.nullableLong(rs, "frictionless_limit"),
                rs.getBoolean("requires_notification"),
                MandateDebitStatus.valueOf(rs.getString("status")),
                rs.getString("description"),
                Sql.instant(rs, "due_at"),
                Sql.instant(rs, "not_before"),
                rs.getInt("cycle"),
                rs.getString("notification_reference"),
                Sql.instant(rs, "notification_requested_at"),
                Sql.instant(rs, "notified_at"),
                Sql.instant(rs, "next_action_at"),
                rs.getInt("check_count"),
                Sql.instant(rs, "last_executed_at"),
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                rs.getLong("version"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"));
    }
}
