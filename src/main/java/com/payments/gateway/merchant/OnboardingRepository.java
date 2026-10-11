package com.payments.gateway.merchant;

import com.payments.gateway.shared.Ids;
import com.payments.gateway.shared.crypto.KeyRingCiphertexts;
import com.payments.gateway.shared.jdbc.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OnboardingRepository implements KeyRingCiphertexts {
    public record Profile(String mode, String collectionOwnerId, String activeKycId) {
        public boolean aggregator() {
            return "AGGREGATOR".equals(mode);
        }
    }

    public record Submission(String id, String merchantId, byte[] detailsEncrypted, String status, String submittedBy,
                             Instant submittedAt, Instant validUntil, String reviewedBy, Instant reviewedAt, byte[] detailsHash) {
    }

    private final JdbcClient jdbc;

    public OnboardingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Profile profile(String merchantId, boolean lock) {
        return jdbc.sql("SELECT mode, collection_owner_id, active_kyc_id FROM merchants WHERE id = :id"
                        + (lock ? " FOR SHARE" : ""))
                .param("id", merchantId)
                .query((row, index) -> new Profile(row.getString("mode"), row.getString("collection_owner_id"),
                        row.getString("active_kyc_id"))).single();
    }

    public boolean hasHistory(String merchantId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM payments WHERE merchant_id = :id)
                    OR EXISTS (SELECT 1 FROM mandates WHERE merchant_id = :id)
                    OR EXISTS (SELECT 1 FROM merchant_provider_accounts WHERE merchant_id = :id)
                """).param("id", merchantId).query(Boolean.class).single();
    }

    public void insert(Submission submission) {
        jdbc.sql("""
                INSERT INTO merchant_kyc_submissions
                    (id, merchant_id, details_enc, status, submitted_by, submitted_at, valid_until, details_hash)
                VALUES (:id, :merchant, :details, 'PENDING', :actor, :now, :expires, :hash)
                """).param("id", submission.id()).param("merchant", submission.merchantId())
                .param("details", submission.detailsEncrypted()).param("actor", submission.submittedBy())
                .param("now", Sql.ts(submission.submittedAt())).param("expires", Sql.ts(submission.validUntil()))
                .param("hash", submission.detailsHash()).update();
    }

    public Optional<Submission> find(String merchantId, String id) {
        return jdbc.sql("SELECT * FROM merchant_kyc_submissions WHERE id = :id AND merchant_id = :merchant")
                .param("id", id).param("merchant", merchantId).query(OnboardingRepository::submission).optional();
    }

    public List<Submission> list(String merchantId) {
        return jdbc.sql("""
                SELECT * FROM merchant_kyc_submissions WHERE merchant_id = :merchant ORDER BY submitted_at DESC, id LIMIT 100
                """).param("merchant", merchantId).query(OnboardingRepository::submission).list();
    }

    public void review(String id, String decision, String actor, Instant now) {
        jdbc.sql("""
                UPDATE merchant_kyc_submissions SET status = :decision, reviewed_by = :actor, reviewed_at = :now
                 WHERE id = :id AND status = 'PENDING'
                """).param("decision", decision).param("actor", actor).param("now", Sql.ts(now)).param("id", id).update();
    }

    public void activate(String merchantId, String ownerId, String submissionId) {
        jdbc.sql("""
                UPDATE merchants SET mode = 'AGGREGATOR', collection_owner_id = :owner, active_kyc_id = :submission
                 WHERE id = :merchant
                """).param("owner", ownerId).param("submission", submissionId).param("merchant", merchantId).update();
    }

    public void link(String merchantId, String ownerAccountId, String providerCode, Instant now) {
        jdbc.sql("""
                INSERT INTO merchant_provider_accounts (id, merchant_id, provider_code, status, created_at, credential_owner_id)
                VALUES (:id, :merchant, :provider, 'ACTIVE', :now, :owner)
                ON CONFLICT (merchant_id, provider_code) DO NOTHING
                """).param("id", Ids.newId("mpa")).param("merchant", merchantId).param("provider", providerCode)
                .param("now", Sql.ts(now)).param("owner", ownerAccountId).update();
    }

    public boolean reserved(String paymentId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM aggregation_collection_reservations WHERE payment_id = :payment)")
                .param("payment", paymentId).query(Boolean.class).single();
    }

    public boolean reserve(String merchantId, String paymentId, LocalDate day, String currency, long amount, long limit) {
        Optional<Long> total = jdbc.sql("""
                INSERT INTO aggregation_daily_volume (merchant_id, business_date, currency, used_amount)
                VALUES (:merchant, :day, :currency, :amount)
                ON CONFLICT (merchant_id, business_date, currency) DO UPDATE
                    SET used_amount = aggregation_daily_volume.used_amount + :amount
                    WHERE aggregation_daily_volume.used_amount <= :limit - :amount
                RETURNING used_amount
                """).param("merchant", merchantId).param("day", day).param("currency", currency)
                .param("amount", amount).param("limit", limit).query(Long.class).optional();
        if (total.isEmpty()) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO aggregation_collection_reservations (payment_id, merchant_id, business_date, currency, amount)
                VALUES (:payment, :merchant, :day, :currency, :amount)
                """).param("payment", paymentId).param("merchant", merchantId).param("day", day)
                .param("currency", currency).param("amount", amount).update();
        return true;
    }

    @Override
    public List<Stored> keyRingCiphertexts() {
        return jdbc.sql("SELECT id, details_enc FROM merchant_kyc_submissions ORDER BY id")
                .query((row, index) -> new Stored(row.getString("id"), row.getBytes("details_enc"), context(row.getString("id"))))
                .list();
    }

    @Override
    public boolean swapKeyRingCiphertext(String rowId, byte[] expected, byte[] replacement) {
        return jdbc.sql("UPDATE merchant_kyc_submissions SET details_enc = :replacement WHERE id = :id AND details_enc = :expected")
                .param("replacement", replacement).param("id", rowId).param("expected", expected).update() == 1;
    }

    static String context(String submissionId) {
        return "merchant_kyc:" + submissionId;
    }

    private static Submission submission(ResultSet row, int index) throws SQLException {
        return new Submission(row.getString("id"), row.getString("merchant_id"), row.getBytes("details_enc"),
                row.getString("status"), row.getString("submitted_by"), Sql.instant(row, "submitted_at"),
                Sql.instant(row, "valid_until"), row.getString("reviewed_by"), Sql.instant(row, "reviewed_at"), row.getBytes("details_hash"));
    }
}