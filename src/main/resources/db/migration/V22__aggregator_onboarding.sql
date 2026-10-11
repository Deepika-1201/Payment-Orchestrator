ALTER TABLE merchants
    ADD COLUMN mode text NOT NULL DEFAULT 'ORCHESTRATOR' CHECK (mode IN ('ORCHESTRATOR', 'AGGREGATOR')),
    ADD COLUMN collection_owner_id text REFERENCES merchants (id),
    ADD COLUMN active_kyc_id text,
    ADD CONSTRAINT ck_merchant_collection_owner CHECK (
        (mode = 'ORCHESTRATOR' AND collection_owner_id IS NULL AND active_kyc_id IS NULL)
        OR (mode = 'AGGREGATOR' AND collection_owner_id IS NOT NULL AND collection_owner_id <> id AND active_kyc_id IS NOT NULL)
    );

CREATE TABLE merchant_kyc_submissions (
    id text PRIMARY KEY,
    merchant_id text NOT NULL REFERENCES merchants (id),
    details_enc bytea NOT NULL,
    details_hash bytea NOT NULL CHECK (octet_length(details_hash) = 32),
    status text NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    submitted_by text NOT NULL CHECK (length(submitted_by) BETWEEN 1 AND 200),
    submitted_at timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    reviewed_by text,
    reviewed_at timestamptz,
    CONSTRAINT ux_kyc_merchant UNIQUE (id, merchant_id),
    CONSTRAINT ck_kyc_dates CHECK (valid_until > submitted_at),
    CONSTRAINT ck_kyc_review CHECK (
        CASE WHEN status = 'PENDING' THEN reviewed_by IS NULL AND reviewed_at IS NULL
        ELSE reviewed_by IS NOT NULL AND length(reviewed_by) BETWEEN 1 AND 200
             AND reviewed_by <> submitted_by AND reviewed_at IS NOT NULL AND reviewed_at >= submitted_at END
    )
);
CREATE INDEX ix_kyc_merchant ON merchant_kyc_submissions (merchant_id, submitted_at DESC, id);
ALTER TABLE merchants ADD CONSTRAINT fk_merchant_active_kyc
    FOREIGN KEY (active_kyc_id, id) REFERENCES merchant_kyc_submissions (id, merchant_id);

CREATE FUNCTION kyc_review_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'KYC submissions are retained';
    END IF;
     IF ROW(NEW.id, NEW.merchant_id, NEW.submitted_by, NEW.submitted_at, NEW.valid_until, NEW.details_hash)
         IS DISTINCT FROM ROW(OLD.id, OLD.merchant_id, OLD.submitted_by, OLD.submitted_at, OLD.valid_until, OLD.details_hash)
       OR (OLD.status <> 'PENDING' AND ROW(NEW.status, NEW.reviewed_by, NEW.reviewed_at)
           IS DISTINCT FROM ROW(OLD.status, OLD.reviewed_by, OLD.reviewed_at)) THEN
        RAISE EXCEPTION 'KYC facts and completed reviews are immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_kyc_review_once BEFORE UPDATE OR DELETE ON merchant_kyc_submissions
    FOR EACH ROW EXECUTE FUNCTION kyc_review_once();

CREATE FUNCTION guard_collection_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.mode, NEW.collection_owner_id) IS DISTINCT FROM ROW(OLD.mode, OLD.collection_owner_id) THEN
        IF OLD.mode = 'AGGREGATOR'
           OR EXISTS (SELECT 1 FROM payments WHERE merchant_id = OLD.id)
           OR EXISTS (SELECT 1 FROM mandates WHERE merchant_id = OLD.id)
           OR EXISTS (SELECT 1 FROM merchant_provider_accounts WHERE merchant_id = OLD.id) THEN
            RAISE EXCEPTION 'collection ownership cannot change after enrollment or financial history';
        END IF;
    END IF;
    IF NEW.mode = 'AGGREGATOR' THEN
        IF NOT EXISTS (SELECT 1 FROM merchants WHERE id = NEW.collection_owner_id AND mode = 'ORCHESTRATOR')
           OR NEW.active_kyc_id IS NULL
           OR NOT EXISTS (SELECT 1 FROM merchant_kyc_submissions
                           WHERE id = NEW.active_kyc_id AND merchant_id = NEW.id AND status = 'APPROVED') THEN
            RAISE EXCEPTION 'aggregator ownership requires an orchestrator owner and approved merchant KYC';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_collection_owner BEFORE UPDATE OF mode, collection_owner_id, active_kyc_id ON merchants
    FOR EACH ROW EXECUTE FUNCTION guard_collection_owner();

CREATE FUNCTION lock_collection_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM merchants WHERE id = NEW.merchant_id FOR SHARE;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_payment_collection_history BEFORE INSERT ON payments
    FOR EACH ROW EXECUTE FUNCTION lock_collection_history();
CREATE TRIGGER trg_mandate_collection_history BEFORE INSERT ON mandates
    FOR EACH ROW EXECUTE FUNCTION lock_collection_history();

ALTER TABLE merchant_provider_accounts ADD COLUMN credential_owner_id text,
    ADD CONSTRAINT ux_provider_account_code UNIQUE (id, provider_code),
    ADD CONSTRAINT fk_provider_credential_owner FOREIGN KEY (credential_owner_id, provider_code)
        REFERENCES merchant_provider_accounts (id, provider_code),
    ADD CONSTRAINT ck_linked_account_credentials CHECK (
        credential_owner_id IS NULL OR (credential_owner_id <> id AND credentials_enc IS NULL)
    );

CREATE FUNCTION guard_provider_credential_owner() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    merchant_mode text;
    owner_id text;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.id, NEW.merchant_id, NEW.provider_code, NEW.credential_owner_id)
            IS DISTINCT FROM ROW(OLD.id, OLD.merchant_id, OLD.provider_code, OLD.credential_owner_id) THEN
            RAISE EXCEPTION 'provider account identity and credential owner are immutable';
        END IF;
        RETURN NEW;
    END IF;
    SELECT mode, collection_owner_id INTO merchant_mode, owner_id FROM merchants WHERE id = NEW.merchant_id FOR SHARE;
    IF merchant_mode = 'AGGREGATOR' THEN
        IF NEW.credential_owner_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM merchant_provider_accounts
             WHERE id = NEW.credential_owner_id AND merchant_id = owner_id
               AND provider_code = NEW.provider_code AND credential_owner_id IS NULL
        ) THEN
            RAISE EXCEPTION 'aggregator provider account must link to its collection owner';
        END IF;
    ELSIF NEW.credential_owner_id IS NOT NULL THEN
        RAISE EXCEPTION 'orchestrator provider accounts cannot borrow credentials';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_provider_credential_owner BEFORE INSERT OR UPDATE ON merchant_provider_accounts
    FOR EACH ROW EXECUTE FUNCTION guard_provider_credential_owner();
CREATE TRIGGER trg_provider_account_retained BEFORE DELETE ON merchant_provider_accounts
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE aggregation_daily_volume (
    merchant_id text NOT NULL REFERENCES merchants (id),
    business_date date NOT NULL,
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    used_amount bigint NOT NULL CHECK (used_amount BETWEEN 1 AND 1000000000000),
    PRIMARY KEY (merchant_id, business_date, currency)
);
CREATE TABLE aggregation_collection_reservations (
    payment_id text PRIMARY KEY REFERENCES payments (id),
    merchant_id text NOT NULL,
    business_date date NOT NULL,
    currency char(3) NOT NULL,
    amount bigint NOT NULL CHECK (amount > 0),
    FOREIGN KEY (merchant_id, business_date, currency)
        REFERENCES aggregation_daily_volume (merchant_id, business_date, currency)
);
CREATE TRIGGER trg_collection_reservation_immutable BEFORE UPDATE OR DELETE ON aggregation_collection_reservations
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();