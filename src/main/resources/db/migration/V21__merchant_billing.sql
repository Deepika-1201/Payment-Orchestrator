CREATE TABLE billing_accounts (
    merchant_id text PRIMARY KEY REFERENCES merchants (id),
    enrolled_at timestamptz NOT NULL,
    next_month date NOT NULL CHECK (extract(day FROM next_month) = 1)
);

CREATE TABLE billing_plans (
    id text PRIMARY KEY,
    merchant_id text NOT NULL REFERENCES billing_accounts (merchant_id),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    effective_at timestamptz NOT NULL,
    monthly_minimum bigint NOT NULL CHECK (monthly_minimum BETWEEN 0 AND 1000000000000),
    created_at timestamptz NOT NULL,
    CONSTRAINT ux_billing_plan_time UNIQUE (merchant_id, effective_at),
    CONSTRAINT ux_billing_plan_merchant UNIQUE (id, merchant_id),
    CONSTRAINT ck_billing_plan_prospective CHECK (effective_at >= created_at)
);

CREATE TABLE billing_plan_prices (
    plan_id text NOT NULL REFERENCES billing_plans (id),
    kind text NOT NULL CHECK (kind IN ('PAYMENT', 'REFUND')),
    method text NOT NULL CHECK (method IN ('UPI', 'CARD', 'NETBANKING', 'MANDATE', 'WALLET', 'EMI',
                                          'CARDLESS_EMI', 'PAY_LATER', 'BANK_TRANSFER')),
    fixed_amount bigint NOT NULL CHECK (fixed_amount BETWEEN 0 AND 1000000000000),
    rate_bps integer NOT NULL CHECK (rate_bps BETWEEN 0 AND 10000),
    PRIMARY KEY (plan_id, kind, method)
);

CREATE TRIGGER trg_billing_plans_immutable BEFORE UPDATE OR DELETE ON billing_plans
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_billing_prices_immutable BEFORE UPDATE OR DELETE ON billing_plan_prices
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE billing_events (
    id text PRIMARY KEY,
    merchant_id text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('PAYMENT', 'REFUND')),
    reference_id text NOT NULL,
    payment_id text NOT NULL,
    plan_id text NOT NULL,
    method text NOT NULL CHECK (method IN ('UPI', 'CARD', 'NETBANKING', 'MANDATE', 'WALLET', 'EMI',
                                          'CARDLESS_EMI', 'PAY_LATER', 'BANK_TRANSFER')),
    amount bigint NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    settled_amount bigint CHECK (settled_amount > 0),
    service_fee bigint CHECK (service_fee >= 0),
    price_reason text,
    recognized_at timestamptz NOT NULL,
    CONSTRAINT ux_billing_event_source UNIQUE (kind, reference_id),
    CONSTRAINT ux_billing_event_merchant UNIQUE (id, merchant_id),
    CONSTRAINT fk_billing_event_plan FOREIGN KEY (plan_id, merchant_id) REFERENCES billing_plans (id, merchant_id),
    CONSTRAINT ck_billing_event_price CHECK (service_fee IS NULL OR settled_amount IS NOT NULL),
    CONSTRAINT ck_billing_event_inr CHECK (currency <> 'INR' OR (settled_amount IS NOT NULL AND settled_amount = amount)),
    CONSTRAINT ck_billing_event_reason CHECK (price_reason IS NULL OR service_fee IS NOT NULL)
);
CREATE INDEX ix_billing_events_merchant_time ON billing_events (merchant_id, recognized_at, id);

CREATE FUNCTION billing_event_complete_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'billing events are retained';
    END IF;
    IF ROW(NEW.id, NEW.merchant_id, NEW.kind, NEW.reference_id, NEW.payment_id, NEW.plan_id, NEW.method,
           NEW.amount, NEW.currency, NEW.recognized_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.merchant_id, OLD.kind, OLD.reference_id, OLD.payment_id, OLD.plan_id, OLD.method,
                            OLD.amount, OLD.currency, OLD.recognized_at)
       OR (OLD.settled_amount IS NOT NULL AND NEW.settled_amount IS DISTINCT FROM OLD.settled_amount)
       OR (OLD.service_fee IS NOT NULL AND NEW.service_fee IS DISTINCT FROM OLD.service_fee)
       OR (OLD.price_reason IS NOT NULL AND NEW.price_reason IS DISTINCT FROM OLD.price_reason)
       OR (NEW.price_reason IS DISTINCT FROM OLD.price_reason AND OLD.service_fee IS NOT NULL) THEN
        RAISE EXCEPTION 'billing event facts and completed pricing are immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_billing_events_complete_only BEFORE UPDATE OR DELETE ON billing_events
    FOR EACH ROW EXECUTE FUNCTION billing_event_complete_only();

CREATE TABLE billing_series (
    series text NOT NULL CHECK (series IN ('INV', 'CN')),
    financial_year integer NOT NULL CHECK (financial_year BETWEEN 1000 AND 9999),
    last_value bigint NOT NULL CHECK (last_value BETWEEN 0 AND 9999999),
    PRIMARY KEY (series, financial_year)
);

CREATE TABLE billing_invoices (
    id text PRIMARY KEY,
    merchant_id text NOT NULL REFERENCES billing_accounts (merchant_id),
    month date NOT NULL CHECK (extract(day FROM month) = 1),
    number varchar(16) NOT NULL UNIQUE,
    financial_year integer NOT NULL,
    sequence bigint NOT NULL CHECK (sequence BETWEEN 1 AND 9999999),
    currency char(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    minimum_plan_id text NOT NULL,
    subtotal bigint NOT NULL CHECK (subtotal >= 0),
    tax bigint NOT NULL CHECK (tax = round(subtotal::numeric * 0.18)),
    total bigint NOT NULL CHECK (total = subtotal + tax),
    collection_mode text NOT NULL CHECK (collection_mode IN ('MERCHANT_PAYMENT', 'SETTLEMENT_DEDUCTION')),
    issued_at timestamptz NOT NULL,
    created_in xid8 NOT NULL DEFAULT pg_current_xact_id(),
    CONSTRAINT ux_billing_invoice_month UNIQUE (merchant_id, month),
    CONSTRAINT ux_billing_invoice_series UNIQUE (financial_year, sequence),
    CONSTRAINT ux_billing_invoice_merchant UNIQUE (id, merchant_id),
    CONSTRAINT fk_invoice_minimum_plan FOREIGN KEY (minimum_plan_id, merchant_id) REFERENCES billing_plans (id, merchant_id)
);

CREATE TABLE billing_invoice_lines (
    id text PRIMARY KEY,
    invoice_id text NOT NULL,
    merchant_id text NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    kind text NOT NULL CHECK (kind IN ('PAYMENT', 'REFUND', 'MINIMUM')),
    plan_id text NOT NULL,
    method text,
    quantity bigint NOT NULL CHECK (quantity > 0),
    amount bigint NOT NULL CHECK (amount >= 0),
    tax bigint NOT NULL CHECK (tax >= 0),
    first_activity_at timestamptz,
    last_activity_at timestamptz,
    CONSTRAINT fk_invoice_line_document FOREIGN KEY (invoice_id, merchant_id) REFERENCES billing_invoices (id, merchant_id),
    CONSTRAINT fk_invoice_line_plan FOREIGN KEY (plan_id, merchant_id) REFERENCES billing_plans (id, merchant_id),
    CONSTRAINT ux_invoice_line_scope UNIQUE (id, invoice_id, merchant_id),
    CONSTRAINT ux_invoice_line_position UNIQUE (invoice_id, position),
    CONSTRAINT ck_invoice_line_kind CHECK (CASE WHEN kind = 'MINIMUM' THEN
        method IS NULL AND quantity = 1 AND amount > 0 AND first_activity_at IS NULL AND last_activity_at IS NULL
           ELSE method IS NOT NULL AND first_activity_at IS NOT NULL AND last_activity_at IS NOT NULL
               AND last_activity_at >= first_activity_at END)
);

CREATE TABLE billing_event_assignments (
    event_id text PRIMARY KEY,
    merchant_id text NOT NULL,
    invoice_id text NOT NULL,
    line_id text NOT NULL,
    FOREIGN KEY (event_id, merchant_id) REFERENCES billing_events (id, merchant_id),
    FOREIGN KEY (line_id, invoice_id, merchant_id) REFERENCES billing_invoice_lines (id, invoice_id, merchant_id)
);
CREATE INDEX ix_billing_assignments_invoice ON billing_event_assignments (invoice_id, line_id);

CREATE FUNCTION billing_invoice_child_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT created_in FROM billing_invoices WHERE id = NEW.invoice_id) IS DISTINCT FROM pg_current_xact_id() THEN
        RAISE EXCEPTION 'issued billing invoice is immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_invoice_line_new_document BEFORE INSERT ON billing_invoice_lines
    FOR EACH ROW EXECUTE FUNCTION billing_invoice_child_insert();
CREATE TRIGGER trg_invoice_assignment_new_document BEFORE INSERT ON billing_event_assignments
    FOR EACH ROW EXECUTE FUNCTION billing_invoice_child_insert();
CREATE TRIGGER trg_billing_invoice_immutable BEFORE UPDATE OR DELETE ON billing_invoices
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_billing_invoice_line_immutable BEFORE UPDATE OR DELETE ON billing_invoice_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_billing_assignment_immutable BEFORE UPDATE OR DELETE ON billing_event_assignments
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE FUNCTION billing_invoice_consistent() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actual_base numeric;
    actual_tax numeric;
    event_base numeric;
    minimum_base numeric;
    minimum_due bigint;
BEGIN
    SELECT COALESCE(sum(amount), 0), COALESCE(sum(tax), 0),
           COALESCE(sum(amount) FILTER (WHERE kind <> 'MINIMUM'), 0),
           COALESCE(sum(amount) FILTER (WHERE kind = 'MINIMUM'), 0)
      INTO actual_base, actual_tax, event_base, minimum_base
      FROM billing_invoice_lines WHERE invoice_id = NEW.id;
    SELECT monthly_minimum INTO minimum_due FROM billing_plans WHERE id = NEW.minimum_plan_id;
    IF actual_base <> NEW.subtotal OR actual_tax <> NEW.tax
       OR minimum_base <> greatest(0, minimum_due - event_base) THEN
        RAISE EXCEPTION 'billing invoice totals or minimum do not match its lines';
    END IF;
    IF EXISTS (
        SELECT 1 FROM billing_invoice_lines line
        LEFT JOIN (
            SELECT assignment.line_id, count(*) AS quantity, sum(event.service_fee) AS amount
              FROM billing_event_assignments assignment JOIN billing_events event ON event.id = assignment.event_id
             WHERE assignment.invoice_id = NEW.id GROUP BY assignment.line_id
        ) actual ON actual.line_id = line.id
        WHERE line.invoice_id = NEW.id AND line.kind <> 'MINIMUM'
          AND (actual.quantity IS NULL OR actual.quantity <> line.quantity OR actual.amount <> line.amount)
    ) OR EXISTS (
        SELECT 1 FROM billing_event_assignments assignment
        JOIN billing_events event ON event.id = assignment.event_id
        JOIN billing_invoice_lines line ON line.id = assignment.line_id
        WHERE assignment.invoice_id = NEW.id AND (event.service_fee IS NULL OR event.settled_amount IS NULL
            OR event.plan_id <> line.plan_id OR event.kind <> line.kind OR event.method IS DISTINCT FROM line.method
            OR event.recognized_at >= ((NEW.month + interval '1 month') AT TIME ZONE 'Asia/Kolkata'))
    ) THEN
        RAISE EXCEPTION 'billing invoice lines do not match assigned service events';
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_billing_invoice_consistent AFTER INSERT ON billing_invoices
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION billing_invoice_consistent();

CREATE TABLE billing_credit_notes (
    id text PRIMARY KEY,
    merchant_id text NOT NULL,
    invoice_id text NOT NULL,
    reference text NOT NULL CHECK (length(reference) BETWEEN 1 AND 128),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    number varchar(16) NOT NULL UNIQUE,
    financial_year integer NOT NULL,
    sequence bigint NOT NULL CHECK (sequence BETWEEN 1 AND 9999999),
    currency char(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    subtotal bigint NOT NULL CHECK (subtotal > 0),
    tax bigint NOT NULL CHECK (tax >= 0),
    total bigint NOT NULL CHECK (total = subtotal + tax),
    issued_at timestamptz NOT NULL,
    created_in xid8 NOT NULL DEFAULT pg_current_xact_id(),
    FOREIGN KEY (invoice_id, merchant_id) REFERENCES billing_invoices (id, merchant_id),
    CONSTRAINT ux_credit_reference UNIQUE (merchant_id, reference),
    CONSTRAINT ux_credit_series UNIQUE (financial_year, sequence),
    CONSTRAINT ux_credit_scope UNIQUE (id, invoice_id, merchant_id)
);

CREATE TABLE billing_credit_lines (
    id text PRIMARY KEY,
    credit_note_id text NOT NULL,
    invoice_id text NOT NULL,
    merchant_id text NOT NULL,
    invoice_line_id text NOT NULL,
    amount bigint NOT NULL CHECK (amount > 0),
    tax bigint NOT NULL CHECK (tax >= 0),
    FOREIGN KEY (credit_note_id, invoice_id, merchant_id) REFERENCES billing_credit_notes (id, invoice_id, merchant_id),
    FOREIGN KEY (invoice_line_id, invoice_id, merchant_id) REFERENCES billing_invoice_lines (id, invoice_id, merchant_id),
    CONSTRAINT ux_credit_line UNIQUE (credit_note_id, invoice_line_id)
);
CREATE INDEX ix_billing_credit_line_original ON billing_credit_lines (invoice_line_id);

CREATE FUNCTION billing_credit_child_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT created_in FROM billing_credit_notes WHERE id = NEW.credit_note_id) IS DISTINCT FROM pg_current_xact_id() THEN
        RAISE EXCEPTION 'issued billing credit note is immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_credit_line_new_document BEFORE INSERT ON billing_credit_lines
    FOR EACH ROW EXECUTE FUNCTION billing_credit_child_insert();
CREATE TRIGGER trg_billing_credit_immutable BEFORE UPDATE OR DELETE ON billing_credit_notes
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_billing_credit_line_immutable BEFORE UPDATE OR DELETE ON billing_credit_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE FUNCTION billing_credit_consistent() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actual_base numeric;
    actual_tax numeric;
BEGIN
    SELECT COALESCE(sum(amount), 0), COALESCE(sum(tax), 0) INTO actual_base, actual_tax
      FROM billing_credit_lines WHERE credit_note_id = NEW.id;
    IF actual_base <> NEW.subtotal OR actual_tax <> NEW.tax THEN
        RAISE EXCEPTION 'billing credit totals do not match its lines';
    END IF;
    IF EXISTS (
        SELECT 1 FROM billing_invoice_lines original
        JOIN billing_credit_lines credited ON credited.invoice_line_id = original.id
        WHERE original.invoice_id = NEW.invoice_id
        GROUP BY original.id, original.amount, original.tax
        HAVING sum(credited.amount) > original.amount OR sum(credited.tax) > original.tax
            OR sum(credited.tax) <> round(original.tax::numeric * sum(credited.amount) / NULLIF(original.amount, 0))
    ) THEN
        RAISE EXCEPTION 'billing credit exceeds the original invoice line';
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_billing_credit_consistent AFTER INSERT ON billing_credit_notes
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION billing_credit_consistent();