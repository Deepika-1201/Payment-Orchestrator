-- Bank transfers to a virtual account per attempt (requirements §8.4, ADR-038).

ALTER TABLE payment_attempts DROP CONSTRAINT payment_attempts_method_type_check;
ALTER TABLE payment_attempts ADD CONSTRAINT payment_attempts_method_type_check
    CHECK (method_type IN ('UPI', 'CARD', 'NETBANKING', 'MANDATE', 'WALLET', 'EMI', 'CARDLESS_EMI', 'PAY_LATER',
                           'BANK_TRANSFER'));

ALTER TABLE merchants ADD COLUMN bank_transfer_credits text NOT NULL DEFAULT 'ADD_UP'
    CHECK (bank_transfer_credits IN ('ADD_UP', 'EXACT'));
ALTER TABLE merchants ADD COLUMN bank_transfer_short_at_expiry text NOT NULL DEFAULT 'REFUND'
    CHECK (bank_transfer_short_at_expiry IN ('REFUND', 'ACCEPT'));

-- Each transfer that arrived, once per PSP id. A credit no attempt can be found for waits for review.
CREATE TABLE transfer_credits (
    id                    text PRIMARY KEY,
    merchant_id           text        NOT NULL REFERENCES merchants (id),
    provider_code         text        NOT NULL,
    provider_reference    text        NOT NULL,
    collection_reference  text,
    attempt_id            text        REFERENCES payment_attempts (id),
    payment_id            text        REFERENCES payments (id),
    amount                bigint      NOT NULL CHECK (amount > 0),
    currency              char(3)     NOT NULL,
    mode                  text        CHECK (mode IN ('NEFT', 'RTGS', 'IMPS', 'UPI')),
    utr                   text,
    received_at           timestamptz NOT NULL,
    applied_amount        bigint      NOT NULL CHECK (applied_amount >= 0),
    returned_amount       bigint      NOT NULL CHECK (returned_amount >= 0),
    return_refund_id      text,
    needs_review          boolean     NOT NULL DEFAULT false,
    review_reason         text,
    flagged_at            timestamptz,
    version               bigint      NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT ux_transfer_credits_provider_ref UNIQUE (provider_code, provider_reference),
    CONSTRAINT ck_transfer_credit_allocation CHECK (applied_amount + returned_amount <= amount),
    CONSTRAINT ck_transfer_credit_placed CHECK ((attempt_id IS NULL) = (payment_id IS NULL)),
    CONSTRAINT ck_transfer_credit_unmatched CHECK (attempt_id IS NOT NULL OR (applied_amount = 0 AND returned_amount = 0)),
    CONSTRAINT ck_transfer_credit_return CHECK ((returned_amount > 0) = (return_refund_id IS NOT NULL))
);
CREATE INDEX ix_transfer_credits_attempt ON transfer_credits (attempt_id) WHERE attempt_id IS NOT NULL;
CREATE INDEX ix_transfer_credits_scope ON transfer_credits (merchant_id, provider_code, received_at);
CREATE INDEX ix_transfer_credits_review ON transfer_credits (flagged_at) WHERE needs_review;

-- A return is a refund against one credit; credit and refund refer to each other, checked at commit.
ALTER TABLE refunds ADD COLUMN credit_id text REFERENCES transfer_credits (id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE refunds DROP CONSTRAINT refunds_initiated_by_check;
ALTER TABLE refunds ADD CONSTRAINT refunds_initiated_by_check
    CHECK (initiated_by IN ('MERCHANT', 'SYSTEM_LATE_SUCCESS', 'SYSTEM_DUPLICATE_SUCCESS', 'SYSTEM_CREDIT_RETURN'));
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_credit_return
    CHECK (initiated_by <> 'SYSTEM_CREDIT_RETURN' OR credit_id IS NOT NULL);
CREATE INDEX ix_refunds_credit ON refunds (credit_id) WHERE credit_id IS NOT NULL;
ALTER TABLE transfer_credits ADD CONSTRAINT fk_transfer_credits_return
    FOREIGN KEY (return_refund_id) REFERENCES refunds (id) DEFERRABLE INITIALLY DEFERRED;

-- Customer money held until it pays for something or goes back (LLD §21.5).
ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_accounts_type_check;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_type_check
    CHECK (type IN ('PSP_RECEIVABLE', 'SALES_CLEARING', 'PSP_FEES', 'REFUNDS', 'CHARGEBACKS', 'BANK_SETTLEMENTS',
                    'CUSTOMER_FUNDS'));
ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_type_check;
ALTER TABLE ledger_transactions ADD CONSTRAINT ledger_transactions_type_check
    CHECK (type IN ('PAYMENT_CAPTURED', 'REFUND_SUCCEEDED', 'PSP_FEE', 'SETTLEMENT', 'CHARGEBACK', 'REVERSAL',
                    'ADJUSTMENT', 'CREDIT_RECEIVED', 'CREDIT_APPLIED', 'CREDIT_RETURNED'));
