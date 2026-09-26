-- Retention (ADR-015): indexes for batched deletes of expired operational rows.

CREATE INDEX ix_provider_webhook_received ON provider_webhook_events (received_at);
CREATE INDEX ix_merchant_events_created ON merchant_events (created_at);
CREATE INDEX ix_webhook_deliveries_created ON webhook_deliveries (created_at);
