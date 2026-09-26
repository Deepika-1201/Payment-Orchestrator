-- Non-sensitive card metadata from the PSP (FR-PM2, ADR-008): network and last 4 digits only.

ALTER TABLE payment_attempts
    ADD COLUMN card_network text,
    ADD COLUMN card_last4   text CHECK (card_last4 ~ '^[0-9]{4}$');
