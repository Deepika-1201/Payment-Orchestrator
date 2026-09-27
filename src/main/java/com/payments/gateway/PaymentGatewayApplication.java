package com.payments.gateway;

import com.payments.gateway.platform.MigrationTask;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentGatewayApplication {

    public static void main(String[] args) {
        if (MigrationTask.requested(System.getenv())) {
            System.exit(MigrationTask.run(args));
        }
        SpringApplication.run(PaymentGatewayApplication.class, args);
    }
}
