-- Dispute responses and evidence files (phase 22, ADR-039, LLD §22.8).

ALTER TABLE disputes
    ADD COLUMN response                  text CHECK (response IN ('CONTEST', 'ACCEPT')),
    ADD COLUMN response_status           text CHECK (response_status IN ('PENDING', 'SENT', 'FAILED')),
    ADD COLUMN response_statement        text,
    ADD COLUMN response_file_ids         text[],
    ADD COLUMN response_requested_at     timestamptz,
    ADD COLUMN response_sent_at          timestamptz,
    ADD COLUMN response_failure          text,
    ADD COLUMN response_attempts         integer     NOT NULL DEFAULT 0 CHECK (response_attempts >= 0),
    ADD COLUMN response_next_attempt_at  timestamptz,
    ADD COLUMN evidence_due_notified_at  timestamptz;

-- Written per value with CASE: a plain boolean expression that evaluates to NULL would pass the check.
ALTER TABLE disputes ADD CONSTRAINT ck_dispute_response_content CHECK (
    CASE response
        WHEN 'CONTEST' THEN coalesce(char_length(response_statement) BETWEEN 1 AND 1000
                                     AND cardinality(response_file_ids) BETWEEN 1 AND 10, false)
        ELSE response_statement IS NULL AND response_file_ids IS NULL
    END);

ALTER TABLE disputes ADD CONSTRAINT ck_dispute_response_status CHECK (
    CASE response_status
        WHEN 'PENDING' THEN response IS NOT NULL AND response_requested_at IS NOT NULL
                            AND response_next_attempt_at IS NOT NULL AND response_sent_at IS NULL
                            AND response_failure IS NULL
        WHEN 'SENT' THEN response IS NOT NULL AND response_requested_at IS NOT NULL
                         AND response_sent_at IS NOT NULL AND response_next_attempt_at IS NULL
                         AND response_failure IS NULL
        WHEN 'FAILED' THEN response IS NOT NULL AND response_requested_at IS NOT NULL
                           AND response_failure IS NOT NULL AND response_next_attempt_at IS NULL
                           AND response_sent_at IS NULL
        ELSE response IS NULL AND response_requested_at IS NULL AND response_sent_at IS NULL
             AND response_failure IS NULL AND response_next_attempt_at IS NULL
    END);

CREATE INDEX ix_disputes_response_due ON disputes (response_next_attempt_at) WHERE response_status = 'PENDING';
CREATE INDEX ix_disputes_open_deadline ON disputes (respond_by) WHERE status = 'OPEN';

CREATE TABLE dispute_evidence_files (
    id                    text PRIMARY KEY,
    dispute_id            text        NOT NULL REFERENCES disputes (id),
    merchant_id           text        NOT NULL,
    category              text        NOT NULL CHECK (category IN ('SHIPPING_PROOF', 'BILLING_PROOF',
                                          'CANCELLATION_PROOF', 'CUSTOMER_COMMUNICATION', 'PROOF_OF_SERVICE',
                                          'EXPLANATION_LETTER', 'REFUND_CONFIRMATION', 'ACCESS_ACTIVITY_LOG',
                                          'REFUND_CANCELLATION_POLICY', 'TERMS_AND_CONDITIONS', 'OTHER')),
    file_name             text        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    content_type          text        NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes            integer     NOT NULL CHECK (size_bytes > 0),
    sha256                char(64)    NOT NULL,
    content_enc           bytea       NOT NULL,
    content_key_enc       bytea       NOT NULL,
    provider_document_id  text,
    created_at            timestamptz NOT NULL,
    -- Stored encrypted: AES-GCM adds exactly the IV (12 bytes) and the tag (16 bytes) to the file.
    CONSTRAINT ck_evidence_ciphertext CHECK (octet_length(content_enc) = size_bytes + 28)
);
CREATE INDEX ix_dispute_evidence_files_dispute ON dispute_evidence_files (dispute_id, created_at);
