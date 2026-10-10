package com.payments.gateway.payment.infrastructure;

import com.payments.gateway.payment.domain.EvidenceFile;
import com.payments.gateway.shared.crypto.KeyRingCiphertexts;
import com.payments.gateway.shared.jdbc.Sql;
import com.payments.gateway.shared.model.EvidenceCategory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Dispute evidence files (ADR-039): written once, never deleted; only the PSP's document id and the wrapped key change. */
@Repository
public class EvidenceFileRepository implements KeyRingCiphertexts {

    /** A file with its encrypted content and wrapped key, for delivery to the PSP. */
    public record Stored(EvidenceFile file, byte[] contentEnc, byte[] contentKeyEnc) {
    }

    private static final String COLUMNS = """
            id, dispute_id, merchant_id, category, file_name, content_type, size_bytes, sha256, provider_document_id,
            created_at""";

    private final JdbcClient jdbc;

    public EvidenceFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The authenticated context of a file's content and key: both only decrypt for this file. */
    public static String context(String fileId) {
        return "dispute_evidence:" + fileId;
    }

    public void insert(EvidenceFile file, byte[] contentEnc, byte[] contentKeyEnc) {
        jdbc.sql("""
                INSERT INTO dispute_evidence_files (id, dispute_id, merchant_id, category, file_name, content_type,
                                                    size_bytes, sha256, content_enc, content_key_enc, created_at)
                VALUES (:id, :disputeId, :merchantId, :category, :fileName, :contentType, :size, :sha256, :content,
                        :contentKey, :createdAt)
                """)
                .param("id", file.id())
                .param("disputeId", file.disputeId())
                .param("merchantId", file.merchantId())
                .param("category", file.category().name())
                .param("fileName", file.fileName())
                .param("contentType", file.contentType())
                .param("size", file.size())
                .param("sha256", file.sha256())
                .param("content", contentEnc)
                .param("contentKey", contentKeyEnc)
                .param("createdAt", Sql.ts(file.createdAt()))
                .update();
    }

    public List<EvidenceFile> findByDispute(String disputeId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dispute_evidence_files WHERE dispute_id = :disputeId ORDER BY created_at, id")
                .param("disputeId", disputeId)
                .query(EvidenceFileRepository::map)
                .list();
    }

    public int countForDispute(String disputeId) {
        return jdbc.sql("SELECT count(*) FROM dispute_evidence_files WHERE dispute_id = :disputeId")
                .param("disputeId", disputeId)
                .query(Integer.class)
                .single();
    }

    /** The listed files of a dispute with their encrypted content. */
    public List<Stored> findWithContent(String disputeId, List<String> ids) {
        return jdbc.sql("SELECT " + COLUMNS + """
                , content_enc, content_key_enc
                  FROM dispute_evidence_files
                 WHERE dispute_id = :disputeId AND id = ANY(:ids)
                 ORDER BY created_at, id
                """)
                .param("disputeId", disputeId)
                .param("ids", ids.toArray(String[]::new))
                .query((rs, n) -> new Stored(map(rs, n), rs.getBytes("content_enc"), rs.getBytes("content_key_enc")))
                .list();
    }

    public void setProviderDocumentId(String fileId, String documentId) {
        jdbc.sql("UPDATE dispute_evidence_files SET provider_document_id = :documentId WHERE id = :id")
                .param("id", fileId)
                .param("documentId", documentId)
                .update();
    }

    @Override
    public List<KeyRingCiphertexts.Stored> keyRingCiphertexts() {
        return jdbc.sql("SELECT id, content_key_enc FROM dispute_evidence_files ORDER BY id")
                .query((rs, n) -> new KeyRingCiphertexts.Stored(rs.getString("id"), rs.getBytes("content_key_enc"),
                        context(rs.getString("id"))))
                .list();
    }

    @Override
    public boolean swapKeyRingCiphertext(String rowId, byte[] expected, byte[] replacement) {
        return jdbc.sql("""
                UPDATE dispute_evidence_files SET content_key_enc = :replacement
                 WHERE id = :id AND content_key_enc = :expected
                """)
                .param("id", rowId)
                .param("expected", expected)
                .param("replacement", replacement)
                .update() == 1;
    }

    private static EvidenceFile map(ResultSet rs, int rowNum) throws SQLException {
        return new EvidenceFile(rs.getString("id"), rs.getString("dispute_id"), rs.getString("merchant_id"),
                EvidenceCategory.valueOf(rs.getString("category")), rs.getString("file_name"),
                rs.getString("content_type"), rs.getInt("size_bytes"), rs.getString("sha256"),
                rs.getString("provider_document_id"), Sql.instant(rs, "created_at"));
    }
}
