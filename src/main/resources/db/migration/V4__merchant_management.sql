-- Merchant management and per-merchant PSP credentials (ADR-014).

-- Webhook secret rotation keeps the previous secret valid for a grace period; deliveries are signed with both.
ALTER TABLE merchants
    ADD COLUMN status_reason                      text,
    ADD COLUMN previous_webhook_secret_enc        bytea,
    ADD COLUMN previous_webhook_secret_expires_at timestamptz;

-- Hourly-granularity usage, so an old key can be revoked once it has stopped being used.
ALTER TABLE api_keys
    ADD COLUMN last_used_at timestamptz;

-- credentials_enc (V1) holds AES-GCM-encrypted JSON bound to the account id; secrets are never readable via the API.
ALTER TABLE merchant_provider_accounts
    ADD COLUMN credentials_updated_at timestamptz,
    ADD COLUMN disabled_at            timestamptz;

-- Webhooks received on a merchant's own account endpoint are scoped to that merchant and deduplicated per account,
-- so one tenant can neither affect another's payments nor pre-empt its event ids.
ALTER TABLE provider_webhook_events
    ADD COLUMN merchant_account_id text,
    ADD COLUMN merchant_id         text;
ALTER TABLE provider_webhook_events DROP CONSTRAINT ux_provider_event;
ALTER TABLE provider_webhook_events
    ADD CONSTRAINT ux_provider_event UNIQUE NULLS NOT DISTINCT (provider_code, merchant_account_id, provider_event_id);
