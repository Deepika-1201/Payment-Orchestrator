package com.payments.gateway.billing;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BillingJob {

    public record RunResult(int accounts, int completed, int blocked) {
    }

    private static final Logger log = LoggerFactory.getLogger(BillingJob.class);
    private final BillingRepository repository;
    private final BillingService billing;
    private final BillingProperties properties;
    private final Clock clock;
    private String afterMerchant = "";

    public BillingJob(BillingRepository repository, BillingService billing, BillingProperties properties, Clock clock) {
        this.repository = repository;
        this.billing = billing;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized RunResult run() {
        YearMonth closed = YearMonth.from(clock.instant().atZone(BillingAmounts.ZONE)).minusMonths(1);
        List<BillingRepository.Account> accounts = repository.dueAccounts(closed, afterMerchant, properties.accountBatch());
        if (accounts.isEmpty() && !afterMerchant.isEmpty()) {
            afterMerchant = "";
            accounts = repository.dueAccounts(closed, afterMerchant, properties.accountBatch());
        }
        int completed = 0;
        int blocked = 0;
        for (BillingRepository.Account account : accounts) {
            afterMerchant = account.merchantId();
            YearMonth month = account.nextMonth();
            for (int count = 0; count < properties.monthsPerAccount() && !month.isAfter(closed); count++) {
                try {
                    billing.issue(account.merchantId(), month);
                    completed++;
                    month = month.plusMonths(1);
                } catch (RuntimeException exception) {
                    blocked++;
                    log.warn("Billing blocked for merchant {} month {}: {}", account.merchantId(), month, exception.getMessage());
                    break;
                }
            }
        }
        return new RunResult(accounts.size(), completed, blocked);
    }
}