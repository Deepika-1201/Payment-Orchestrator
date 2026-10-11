package com.payments.gateway.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.billing")
public record BillingProperties(Integer accountBatch, Integer monthsPerAccount) {
    public BillingProperties {
        accountBatch = accountBatch == null ? 100 : accountBatch;
        monthsPerAccount = monthsPerAccount == null ? 12 : monthsPerAccount;
        if (accountBatch < 1 || accountBatch > 1000 || monthsPerAccount < 1 || monthsPerAccount > 120) {
            throw new IllegalArgumentException("billing account batch must be 1..1000 and months per account 1..120");
        }
    }
}