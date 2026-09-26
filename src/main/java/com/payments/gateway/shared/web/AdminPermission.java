package com.payments.gateway.shared.web;

/** What an admin caller may do (ADR-019). Every admin endpoint requires exactly one permission. */
public enum AdminPermission {
    /** Any {@code GET}: merchants (secrets masked), routing, health, deliveries, ledger, reconciliation, reviews. */
    READ,
    /** Onboard and configure merchants: settings, API keys, webhook secrets, PSP credentials. */
    MERCHANTS_WRITE,
    /** Suspend or reactivate a merchant. */
    MERCHANTS_SUSPEND,
    /** Create and change routing rules. */
    ROUTING_WRITE,
    /** Replay webhook deliveries and acknowledge manual reviews. */
    OPERATIONS_WRITE,
    /** Run reconciliation and assign or resolve its exceptions. */
    FINANCE_WRITE
}
