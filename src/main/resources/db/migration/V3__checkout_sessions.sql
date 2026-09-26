-- Hosted checkout sessions (ADR-013). The URL token is a bearer capability for one payment; only its SHA-256 is stored.

CREATE TABLE checkout_sessions (
    id           text PRIMARY KEY,
    merchant_id  text        NOT NULL REFERENCES merchants (id),
    payment_id   text        NOT NULL REFERENCES payments (id),
    token_hash   bytea       NOT NULL UNIQUE,
    return_url   text,
    expires_at   timestamptz NOT NULL,
    created_at   timestamptz NOT NULL
);
CREATE INDEX ix_checkout_sessions_payment ON checkout_sessions (payment_id);
CREATE INDEX ix_checkout_sessions_expiry ON checkout_sessions (expires_at);
