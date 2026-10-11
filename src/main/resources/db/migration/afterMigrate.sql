-- Flyway callback, re-applied after every migration run (ADR-026): least-privilege grants for the application role.
-- Migrations run as the schema owner; the application connects as ${app_role}, which may read and write data but
-- never change the schema, truncate tables, or rewrite append-only history. A no-op when app_role is not set.
DO $$
DECLARE
    app text := '${app_role}';
BEGIN
    IF app = '' OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app);
    EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', app);
    EXECUTE format('REVOKE TRUNCATE, REFERENCES, TRIGGER ON ALL TABLES IN SCHEMA public FROM %I', app);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON ledger_transactions, ledger_entries, audit_log, payment_transitions, mandate_transitions, fx_conversions FROM %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON provider_fee_schedules FROM %I', app);
    EXECUTE format('REVOKE UPDATE ON routing_decisions FROM %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON billing_plans, billing_plan_prices FROM %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON billing_events FROM %I', app);
    EXECUTE format('GRANT UPDATE (settled_amount, service_fee, price_reason) ON billing_events TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON billing_invoices, billing_invoice_lines, billing_event_assignments FROM %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON billing_credit_notes, billing_credit_lines FROM %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON billing_accounts FROM %I', app);
    EXECUTE format('GRANT UPDATE (next_month) ON billing_accounts TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON billing_series FROM %I', app);
    EXECUTE format('GRANT UPDATE (last_value) ON billing_series TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON merchant_kyc_submissions FROM %I', app);
    EXECUTE format('REVOKE DELETE ON merchant_provider_accounts FROM %I', app);
    EXECUTE format('GRANT UPDATE (details_enc, status, reviewed_by, reviewed_at) ON merchant_kyc_submissions TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON aggregation_collection_reservations FROM %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON aggregation_daily_volume FROM %I', app);
    EXECUTE format('GRANT UPDATE (used_amount) ON aggregation_daily_volume TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON ledger_accounts, treasury_movements, treasury_conversions, treasury_sources, settlement_policies FROM %I', app);
    EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON treasury_control FROM %I', app);
    EXECUTE format('GRANT UPDATE (version) ON treasury_control TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON treasury_psp_fees, escrow_receipts, escrow_receipt_allocations FROM %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON settlement_batches, settlement_batch_items FROM %I', app);
    EXECUTE format('GRANT UPDATE (status, decided_by, decided_at, decision_reason) ON settlement_batches TO %I', app);
    EXECUTE format('REVOKE UPDATE ON settlement_source_claims FROM %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON payout_instructions FROM %I', app);
    EXECUTE format('GRANT UPDATE (instruction_enc, status, dispatched_at, provider_reference, bank_reference, reason, checks, next_check_at, updated_at) ON payout_instructions TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON payout_transitions, escrow_bank_statements, escrow_bank_entries, escrow_reconciliation_results FROM %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON payout_webhook_inbox FROM %I', app);
    EXECUTE format('GRANT UPDATE (event_enc, status, attempts, next_check_at) ON payout_webhook_inbox TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON treasury_exceptions FROM %I', app);
    EXECUTE format('GRANT UPDATE (status, resolved_at, resolved_by, resolution) ON treasury_exceptions TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON escrow_adjustments FROM %I', app);
    EXECUTE format('GRANT UPDATE (status, decided_by, decided_at) ON escrow_adjustments TO %I', app);
    EXECUTE format('GRANT UPDATE (bank_cutoff, bank_mismatch) ON treasury_control TO %I', app);
    -- Evidence is kept with the dispute record (ADR-039): only the PSP's document id and the wrapped key may change.
    EXECUTE format('REVOKE UPDATE, DELETE ON dispute_evidence_files FROM %I', app);
    EXECUTE format('GRANT UPDATE (provider_document_id, content_key_enc) ON dispute_evidence_files TO %I', app);
    EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', app);
END
$$;
