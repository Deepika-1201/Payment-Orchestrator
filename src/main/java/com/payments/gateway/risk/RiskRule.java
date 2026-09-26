package com.payments.gateway.risk;

/** A risk signal. External fraud providers implement this too, with their own timeout (timeout = REVIEW). */
public interface RiskRule {

    RiskDecision evaluate(RiskContext context);
}
