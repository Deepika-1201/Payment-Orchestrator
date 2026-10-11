package com.payments.gateway.treasury;

import com.payments.gateway.shared.error.GatewayException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SettlementJob {
    private static final Logger log = LoggerFactory.getLogger(SettlementJob.class);
    private final TreasuryRepository repository;
    private final SettlementService settlements;
    private final TreasuryProperties properties;
    private final Clock clock;
    private String afterMerchant = "";

    public SettlementJob(TreasuryRepository repository, SettlementService settlements, TreasuryProperties properties, Clock clock) {
        this.repository = repository;
        this.settlements = settlements;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized int run() {
        List<String> merchants = repository.merchantsAfter(afterMerchant, properties.batchSize(), clock.instant());
        int prepared = 0;
        for (String merchant : merchants) {
            afterMerchant = merchant;
            try {
                var batch = settlements.prepare(merchant, "daily:" + clock.instant().atZone(SettlementCalendar.ZONE).toLocalDate(), "settlement-worker");
                if ("DRAFT".equals(batch.status())) {
                    prepared++;
                }
            } catch (GatewayException exception) {
                log.info("Settlement held for merchant {}: {}", merchant, exception.getMessage());
            } catch (RuntimeException exception) {
                log.error("Settlement preparation failed for merchant {}", merchant, exception);
            }
        }
        if (merchants.size() < properties.batchSize()) {
            afterMerchant = "";
        }
        return prepared;
    }
}