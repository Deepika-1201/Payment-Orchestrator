package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.payment.domain.AttemptSnapshot;
import com.payments.gateway.payment.domain.AttemptStatus;
import com.payments.gateway.payment.domain.Customer;
import com.payments.gateway.payment.domain.Failure;
import com.payments.gateway.payment.domain.Payment;
import com.payments.gateway.payment.domain.PaymentAttempt;
import com.payments.gateway.payment.domain.PaymentSnapshot;
import com.payments.gateway.payment.domain.PaymentStatus;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.json.JsonCodec;
import com.payments.gateway.shared.model.CaptureMethod;
import com.payments.gateway.shared.model.CardDetails;
import com.payments.gateway.shared.model.FailureCategory;
import com.payments.gateway.shared.model.MethodType;
import com.payments.gateway.shared.model.Money;
import com.payments.gateway.shared.model.NextAction;
import com.payments.gateway.shared.model.PaymentMethod;
import com.payments.gateway.shared.model.UpiFlow;
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

@Repository
public class PaymentRepository {

    public record AttemptLocator(String attemptId, String paymentId, String providerCode, String merchantId) {
    }

    public record ClaimedAttempt(String attemptId, String paymentId) {
    }

    record MethodDetails(String upiFlow, String vpa, String bankCode) {
    }

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public PaymentRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Payment> findById(String id) {
        return load("SELECT * FROM payments WHERE id = :id", Map.of("id", id), false);
    }

    public Optional<Payment> findForMerchant(String merchantId, String id) {
        return load("SELECT * FROM payments WHERE id = :id AND merchant_id = :merchantId",
                Map.of("id", id, "merchantId", merchantId), false);
    }

    /** Locks the payment and its attempts for the rest of the current transaction. */
    public Optional<Payment> lockById(String id) {
        return load("SELECT * FROM payments WHERE id = :id FOR UPDATE", Map.of("id", id), true);
    }

    public Optional<Payment> lockForMerchant(String merchantId, String id) {
        return load("SELECT * FROM payments WHERE id = :id AND merchant_id = :merchantId FOR UPDATE",
                Map.of("id", id, "merchantId", merchantId), true);
    }

    public void save(Payment payment) {
        if (!payment.isNew() && !payment.isDirty()) {
            return;
        }
        PaymentSnapshot s = payment.snapshot();
        if (payment.isNew()) {
            insertPayment(s);
        } else if (jdbc.sql("""
                UPDATE payments
                   SET status = :status, amount_captured = :amountCaptured, amount_refunded = :amountRefunded,
                       succeeded_attempt_id = :succeededAttemptId, cancellation_reason = :cancellationReason,
                       failure_code = :failureCode, failure_message = :failureMessage,
                       authorization_expires_at = :authorizationExpiresAt, version = version + 1, updated_at = :updatedAt
                 WHERE id = :id AND version = :version
                """)
                .param("status", s.status().name())
                .param("amountCaptured", s.amountCaptured())
                .param("amountRefunded", s.amountRefunded())
                .param("succeededAttemptId", s.succeededAttemptId())
                .param("cancellationReason", s.cancellationReason())
                .param("failureCode", s.failureCode())
                .param("failureMessage", s.failureMessage())
                .param("authorizationExpiresAt", Sql.ts(s.authorizationExpiresAt()))
                .param("updatedAt", Sql.ts(s.updatedAt()))
                .param("id", s.id())
                .param("version", s.version())
                .update() != 1) {
            throw new OptimisticLockingFailureException("payment " + s.id() + " was modified concurrently");
        }
        for (PaymentAttempt attempt : payment.attempts()) {
            if (attempt.isNew()) {
                insertAttempt(attempt.snapshot());
            } else if (attempt.isDirty()) {
                updateAttempt(attempt.snapshot());
            }
        }
        payment.markPersisted();
    }

    public Optional<AttemptLocator> findAttemptByProviderReference(String providerCode, String providerReference) {
        return jdbc.sql("""
                SELECT id, payment_id, provider_code, merchant_id FROM payment_attempts
                 WHERE provider_code = :provider AND provider_reference = :reference
                """)
                .param("provider", providerCode)
                .param("reference", providerReference)
                .query(PaymentRepository::mapLocator)
                .optional();
    }

    public Optional<AttemptLocator> findAttemptById(String attemptId) {
        return jdbc.sql("SELECT id, payment_id, provider_code, merchant_id FROM payment_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(PaymentRepository::mapLocator)
                .optional();
    }

    private static AttemptLocator mapLocator(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AttemptLocator(rs.getString("id"), rs.getString("payment_id"), rs.getString("provider_code"),
                rs.getString("merchant_id"));
    }

    /** Claims due status checks with a lease so concurrent workers never process the same attempt. */
    public List<ClaimedAttempt> claimDueAttempts(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                UPDATE payment_attempts SET next_status_check_at = :leaseUntil
                 WHERE id IN (SELECT id FROM payment_attempts
                               WHERE next_status_check_at <= :now
                               ORDER BY next_status_check_at
                               LIMIT :limit
                               FOR UPDATE SKIP LOCKED)
                RETURNING id, payment_id
                """)
                .param("leaseUntil", Sql.ts(leaseUntil))
                .param("now", Sql.ts(now))
                .param("limit", limit)
                .query((rs, n) -> new ClaimedAttempt(rs.getString("id"), rs.getString("payment_id")))
                .list();
    }

    public List<String> findExpirable(Instant now, Instant processingCutoff, int limit) {
        return jdbc.sql("""
                SELECT id FROM payments
                 WHERE (status IN ('REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION') AND expires_at <= :now)
                    OR (status = 'PROCESSING' AND expires_at <= :processingCutoff)
                    OR (status = 'AUTHORIZED' AND authorization_expires_at <= :now)
                 LIMIT :limit
                """)
                .param("now", Sql.ts(now))
                .param("processingCutoff", Sql.ts(processingCutoff))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public int countRecentAttemptsByCustomer(String merchantId, String customerReference, Instant since) {
        return jdbc.sql("""
                SELECT count(*) FROM payment_attempts a JOIN payments p ON p.id = a.payment_id
                 WHERE p.merchant_id = :merchantId AND p.customer_reference = :customerReference AND a.created_at >= :since
                """)
                .param("merchantId", merchantId)
                .param("customerReference", customerReference)
                .param("since", Sql.ts(since))
                .query(Integer.class)
                .single();
    }

    private Optional<Payment> load(String sql, Map<String, ?> params, boolean lock) {
        return jdbc.sql(sql).params(params).query(this::mapPayment).optional()
                .map(snapshot -> Payment.rehydrate(snapshot, loadAttempts(snapshot.id(), lock)));
    }

    private List<PaymentAttempt> loadAttempts(String paymentId, boolean lock) {
        return jdbc.sql("SELECT * FROM payment_attempts WHERE payment_id = :paymentId ORDER BY attempt_number"
                        + (lock ? " FOR UPDATE" : ""))
                .param("paymentId", paymentId)
                .query(this::mapAttempt)
                .list()
                .stream()
                .map(PaymentAttempt::rehydrate)
                .toList();
    }

    private void insertPayment(PaymentSnapshot s) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", s.id());
        params.put("merchantId", s.merchantId());
        params.put("merchantOrderId", s.merchantOrderId());
        params.put("amount", s.amount().amount());
        params.put("currency", s.amount().currency());
        params.put("status", s.status().name());
        params.put("captureMethod", s.captureMethod().name());
        params.put("description", s.description());
        params.put("customerReference", s.customer() == null ? null : s.customer().reference());
        params.put("customerEmail", s.customer() == null ? null : s.customer().email());
        params.put("customerPhone", s.customer() == null ? null : s.customer().phone());
        params.put("metadata", json.write(s.metadata() == null ? Map.of() : s.metadata()));
        params.put("expiresAt", Sql.ts(s.expiresAt()));
        params.put("createdAt", Sql.ts(s.createdAt()));
        params.put("updatedAt", Sql.ts(s.updatedAt()));
        jdbc.sql("""
                INSERT INTO payments (id, merchant_id, merchant_order_id, amount, currency, status, capture_method,
                                      description, customer_reference, customer_email, customer_phone, metadata,
                                      expires_at, version, created_at, updated_at)
                VALUES (:id, :merchantId, :merchantOrderId, :amount, :currency, :status, :captureMethod, :description,
                        :customerReference, :customerEmail, :customerPhone, CAST(:metadata AS jsonb), :expiresAt, 0,
                        :createdAt, :updatedAt)
                """)
                .params(params)
                .update();
    }

    private void insertAttempt(AttemptSnapshot s) {
        Map<String, Object> params = attemptParams(s);
        params.put("paymentId", s.paymentId());
        params.put("merchantId", s.merchantId());
        params.put("attemptNumber", s.attemptNumber());
        params.put("providerCode", s.providerCode());
        params.put("methodType", s.method().type().name());
        params.put("methodDetails", json.write(new MethodDetails(
                s.method().upiFlow() == null ? null : s.method().upiFlow().name(), s.method().vpa(), s.method().bankCode())));
        params.put("amount", s.amount().amount());
        params.put("currency", s.amount().currency());
        params.put("routingRuleId", s.routingRuleId());
        params.put("createdAt", Sql.ts(s.createdAt()));
        jdbc.sql("""
                INSERT INTO payment_attempts (id, payment_id, merchant_id, attempt_number, provider_code, method_type,
                                              method_details, amount, currency, status, provider_reference, next_action,
                                              failure_code, failure_category, failure_message, card_network, card_last4,
                                              routing_rule_id, authorized_at, captured_at, void_requested,
                                              next_status_check_at, status_check_count, needs_review, version,
                                              created_at, updated_at)
                VALUES (:id, :paymentId, :merchantId, :attemptNumber, :providerCode, :methodType,
                        CAST(:methodDetails AS jsonb), :amount, :currency, :status, :providerReference,
                        CAST(:nextAction AS jsonb), :failureCode, :failureCategory, :failureMessage, :cardNetwork,
                        :cardLast4, :routingRuleId, :authorizedAt, :capturedAt, :voidRequested, :nextStatusCheckAt,
                        :statusCheckCount, :needsReview, 0, :createdAt, :updatedAt)
                """)
                .params(params)
                .update();
    }

    private void updateAttempt(AttemptSnapshot s) {
        Map<String, Object> params = attemptParams(s);
        params.put("version", s.version());
        int updated = jdbc.sql("""
                UPDATE payment_attempts
                   SET status = :status, provider_reference = :providerReference, next_action = CAST(:nextAction AS jsonb),
                       failure_code = :failureCode, failure_category = :failureCategory, failure_message = :failureMessage,
                       card_network = :cardNetwork, card_last4 = :cardLast4,
                       authorized_at = :authorizedAt, captured_at = :capturedAt, void_requested = :voidRequested,
                       next_status_check_at = :nextStatusCheckAt, status_check_count = :statusCheckCount,
                       needs_review = :needsReview, version = version + 1, updated_at = :updatedAt
                 WHERE id = :id AND version = :version
                """)
                .params(params)
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("attempt " + s.id() + " was modified concurrently");
        }
    }

    private Map<String, Object> attemptParams(AttemptSnapshot s) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", s.id());
        params.put("status", s.status().name());
        params.put("providerReference", s.providerReference());
        params.put("nextAction", s.nextAction() == null ? null : json.write(s.nextAction()));
        params.put("failureCode", s.failure() == null ? null : s.failure().code());
        params.put("failureCategory", s.failure() == null ? null : s.failure().category().name());
        params.put("failureMessage", s.failure() == null ? null : s.failure().message());
        params.put("cardNetwork", s.card() == null ? null : s.card().network());
        params.put("cardLast4", s.card() == null ? null : s.card().last4());
        params.put("authorizedAt", Sql.ts(s.authorizedAt()));
        params.put("capturedAt", Sql.ts(s.capturedAt()));
        params.put("voidRequested", s.voidRequested());
        params.put("nextStatusCheckAt", Sql.ts(s.nextStatusCheckAt()));
        params.put("statusCheckCount", s.statusCheckCount());
        params.put("needsReview", s.needsReview());
        params.put("updatedAt", Sql.ts(s.updatedAt()));
        return params;
    }

    private PaymentSnapshot mapPayment(ResultSet rs, int rowNum) throws SQLException {
        String currency = rs.getString("currency");
        return new PaymentSnapshot(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("merchant_order_id"),
                Money.of(rs.getLong("amount"), currency),
                PaymentStatus.valueOf(rs.getString("status")),
                CaptureMethod.valueOf(rs.getString("capture_method")),
                rs.getString("description"),
                new Customer(rs.getString("customer_reference"), rs.getString("customer_email"), rs.getString("customer_phone")),
                json.read(rs.getString("metadata"), STRING_MAP),
                rs.getLong("amount_captured"),
                rs.getLong("amount_refunded"),
                rs.getString("succeeded_attempt_id"),
                rs.getString("cancellation_reason"),
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                Sql.instant(rs, "expires_at"),
                Sql.instant(rs, "authorization_expires_at"),
                rs.getLong("version"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"));
    }

    private AttemptSnapshot mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        MethodDetails details = json.read(rs.getString("method_details"), MethodDetails.class);
        PaymentMethod method = new PaymentMethod(MethodType.valueOf(rs.getString("method_type")),
                details.upiFlow() == null ? null : UpiFlow.valueOf(details.upiFlow()), details.vpa(), details.bankCode());
        String nextAction = rs.getString("next_action");
        String failureCategory = rs.getString("failure_category");
        Failure failure = failureCategory == null ? null
                : new Failure(rs.getString("failure_code"), FailureCategory.valueOf(failureCategory), rs.getString("failure_message"));
        return new AttemptSnapshot(
                rs.getString("id"),
                rs.getString("payment_id"),
                rs.getString("merchant_id"),
                rs.getInt("attempt_number"),
                rs.getString("provider_code"),
                method,
                Money.of(rs.getLong("amount"), rs.getString("currency")),
                AttemptStatus.valueOf(rs.getString("status")),
                rs.getString("provider_reference"),
                nextAction == null ? null : json.read(nextAction, NextAction.class),
                failure,
                rs.getString("card_network") == null ? null
                        : new CardDetails(rs.getString("card_network"), rs.getString("card_last4")),
                rs.getString("routing_rule_id"),
                Sql.instant(rs, "authorized_at"),
                Sql.instant(rs, "captured_at"),
                rs.getBoolean("void_requested"),
                Sql.instant(rs, "next_status_check_at"),
                rs.getInt("status_check_count"),
                rs.getBoolean("needs_review"),
                rs.getLong("version"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"));
    }
}
