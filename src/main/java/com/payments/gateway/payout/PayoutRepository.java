package com.payments.gateway.payout;

import com.payments.gateway.shared.crypto.KeyRingCiphertexts;
import com.payments.gateway.shared.jdbc.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PayoutRepository implements KeyRingCiphertexts {
    public record Inbox(String id, String provider, String payoutId, byte[] encrypted, byte[] hash, int attempts) {
    }
    private final JdbcClient jdbc;

    public PayoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PayoutRecord payout) {
        jdbc.sql("""
                INSERT INTO payout_instructions (id, merchant_id, batch_id, kyc_id, provider_code, amount, instruction_enc,
                    instruction_hash, status, next_check_at, created_at, updated_at)
                VALUES (:id, :merchant, :batch, :kyc, :provider, :amount, :instruction, :hash, 'QUEUED', :now, :now, :now)
                """).param("id", payout.id()).param("merchant", payout.merchantId()).param("batch", payout.batchId())
                .param("kyc", payout.kycId()).param("provider", payout.providerCode()).param("amount", payout.amount())
                .param("instruction", payout.instructionEncrypted()).param("hash", payout.instructionHash())
                .param("now", Sql.ts(payout.createdAt())).update();
        transition(payout.id(), null, "QUEUED", null, payout.createdAt());
    }

    public Optional<PayoutRecord> find(String id, boolean lock) {
        return jdbc.sql("SELECT * FROM payout_instructions WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(PayoutRepository::map).optional();
    }

    public Optional<PayoutRecord> byBatch(String merchantId, String batchId) {
        return jdbc.sql("SELECT * FROM payout_instructions WHERE merchant_id = :merchant AND batch_id = :batch")
                .param("merchant", merchantId).param("batch", batchId).query(PayoutRepository::map).optional();
    }

    public List<PayoutRecord> list(String merchantId) {
        return jdbc.sql("SELECT * FROM payout_instructions WHERE merchant_id = :merchant ORDER BY created_at DESC, id DESC LIMIT 100")
                .param("merchant", merchantId).query(PayoutRepository::map).list();
    }

    public boolean dispatch(String id, Instant now, Instant next) {
        boolean changed = jdbc.sql("""
                UPDATE payout_instructions SET dispatched_at = :now, status = 'PENDING', updated_at = :now, next_check_at = :next, reason = NULL
                 WHERE id = :id AND dispatched_at IS NULL AND status = 'QUEUED'
                """).param("id", id).param("now", Sql.ts(now)).param("next", Sql.ts(next)).update() == 1;
        if (changed) {
            transition(id, "QUEUED", "PENDING", "dispatch_started", now);
        }
        return changed;
    }

    public void update(PayoutRecord previous, PayoutState.Status status, String providerReference, String bankReference,
                       String reason, Instant next, Instant now) {
        jdbc.sql("""
                UPDATE payout_instructions SET status = :status, provider_reference = :provider, bank_reference = :bank,
                       reason = :reason, checks = checks + 1, next_check_at = :next, updated_at = :now WHERE id = :id
                """).param("status", status.name()).param("provider", providerReference).param("bank", bankReference)
                .param("reason", reason).param("next", Sql.ts(next)).param("now", Sql.ts(now)).param("id", previous.id()).update();
        if (status != previous.status()) {
            transition(previous.id(), previous.status().name(), status.name(), reason, now);
        }
    }

    public List<String> claim(Instant now, Instant lease, int limit) {
        return jdbc.sql("""
                UPDATE payout_instructions SET next_check_at = :lease WHERE id IN (
                    SELECT id FROM payout_instructions WHERE next_check_at <= :now
                     ORDER BY next_check_at, id FOR UPDATE SKIP LOCKED LIMIT :limit)
                RETURNING id
                """).param("lease", Sql.ts(lease)).param("now", Sql.ts(now)).param("limit", limit).query(String.class).list();
    }

    public List<PayoutRecord> overdue(Instant cutoff) {
        return jdbc.sql("""
                SELECT * FROM payout_instructions WHERE status IN ('QUEUED', 'PENDING', 'REVIEW')
                    AND coalesce(dispatched_at, created_at) <= :cutoff
                    AND NOT EXISTS (SELECT 1 FROM treasury_exceptions exception WHERE exception.type = 'PAYOUT_UNRESOLVED'
                                     AND exception.reference = payout_instructions.id AND exception.status = 'OPEN')
                 ORDER BY created_at, id LIMIT 1000
                """).param("cutoff", Sql.ts(cutoff)).query(PayoutRepository::map).list();
    }

    public long countOverdue(Instant cutoff) {
        return jdbc.sql("""
                SELECT count(*) FROM payout_instructions WHERE status IN ('QUEUED', 'PENDING', 'REVIEW')
                    AND coalesce(dispatched_at, created_at) <= :cutoff
                """).param("cutoff", Sql.ts(cutoff)).query(Long.class).single();
    }

    private void transition(String id, String before, String after, String reason, Instant now) {
        jdbc.sql("""
                INSERT INTO payout_transitions (payout_id, from_status, to_status, reason, occurred_at)
                VALUES (:id, :before, :after, :reason, :now)
                """).param("id", id).param("before", before).param("after", after).param("reason", reason).param("now", Sql.ts(now)).update();
    }

    public boolean inbox(Inbox event, String providerEventId, Instant now) {
        return jdbc.sql("""
                INSERT INTO payout_webhook_inbox (id, provider_code, provider_event_id, payout_id, event_enc, event_hash,
                                                  status, next_check_at, created_at)
                VALUES (:id, :provider, :event, :payout, :body, :hash, 'PENDING', :now, :now)
                ON CONFLICT (provider_code, provider_event_id) DO NOTHING
                """).param("id", event.id()).param("provider", event.provider()).param("event", providerEventId)
                .param("payout", event.payoutId()).param("body", event.encrypted()).param("hash", event.hash())
                .param("now", Sql.ts(now)).update() == 1;
    }

    public List<Inbox> claimInbox(Instant now, Instant lease, int limit) {
        return jdbc.sql("""
                UPDATE payout_webhook_inbox SET next_check_at = :lease WHERE id IN (
                    SELECT id FROM payout_webhook_inbox WHERE status = 'PENDING' AND next_check_at <= :now
                     ORDER BY next_check_at, id FOR UPDATE SKIP LOCKED LIMIT :limit)
                RETURNING *
                """).param("lease", Sql.ts(lease)).param("now", Sql.ts(now)).param("limit", limit)
                .query((row, index) -> new Inbox(row.getString("id"), row.getString("provider_code"), row.getString("payout_id"),
                        row.getBytes("event_enc"), row.getBytes("event_hash"), row.getInt("attempts"))).list();
    }

    public void completeInbox(String id) {
        jdbc.sql("UPDATE payout_webhook_inbox SET status = 'DONE', next_check_at = NULL WHERE id = :id").param("id", id).update();
    }

    public void retryInbox(String id, Instant next) {
        jdbc.sql("UPDATE payout_webhook_inbox SET attempts = attempts + 1, next_check_at = :next WHERE id = :id AND status = 'PENDING'")
                .param("id", id).param("next", Sql.ts(next)).update();
    }

    @Override
    public List<Stored> keyRingCiphertexts() {
        List<Stored> result = new java.util.ArrayList<>(jdbc.sql("SELECT id, instruction_enc FROM payout_instructions ORDER BY id")
            .query((row, index) -> new Stored(row.getString("id"), row.getBytes("instruction_enc"), context(row.getString("id")))).list());
        result.addAll(jdbc.sql("SELECT id, event_enc FROM payout_webhook_inbox ORDER BY id")
            .query((row, index) -> new Stored("event:" + row.getString("id"), row.getBytes("event_enc"), inboxContext(row.getString("id")))).list());
        return List.copyOf(result);
    }

    @Override
    public boolean swapKeyRingCiphertext(String rowId, byte[] expected, byte[] replacement) {
        if (rowId.startsWith("event:")) {
            return jdbc.sql("UPDATE payout_webhook_inbox SET event_enc = :after WHERE id = :id AND event_enc = :before")
                    .param("after", replacement).param("id", rowId.substring(6)).param("before", expected).update() == 1;
        }
        return jdbc.sql("UPDATE payout_instructions SET instruction_enc = :after WHERE id = :id AND instruction_enc = :before")
                .param("after", replacement).param("id", rowId).param("before", expected).update() == 1;
    }

    public static String context(String id) {
        return "payout_instruction:" + id;
    }

    public static String inboxContext(String id) {
        return "payout_webhook:" + id;
    }

    private static PayoutRecord map(ResultSet row, int index) throws SQLException {
        return new PayoutRecord(row.getString("id"), row.getString("merchant_id"), row.getString("batch_id"), row.getString("kyc_id"),
                row.getString("provider_code"), row.getLong("amount"), row.getBytes("instruction_enc"), row.getBytes("instruction_hash"),
                PayoutState.Status.valueOf(row.getString("status")), Sql.instant(row, "dispatched_at"), row.getString("provider_reference"),
                row.getString("bank_reference"), row.getString("reason"), row.getInt("checks"), Sql.instant(row, "next_check_at"),
                Sql.instant(row, "created_at"), Sql.instant(row, "updated_at"));
    }
}