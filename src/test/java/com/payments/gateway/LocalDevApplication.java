package com.payments.gateway;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;

/**
 * Runs the gateway locally without Docker: {@code ./gradlew bootTestRun}. Starts an embedded PostgreSQL whose data
 * survives restarts under {@code .embedded-pg/}, then the application with the {@code local} profile.
 */
public final class LocalDevApplication {

    private LocalDevApplication() {
    }

    public static void main(String[] args) throws IOException {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setDataDirectory(Path.of(".embedded-pg"))
                .setCleanDataDirectory(false)
                .setPort(55432)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (IOException ignored) {
                // best effort on shutdown
            }
        }));
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        SpringApplication application = new SpringApplication(PaymentGatewayApplication.class);
        application.setAdditionalProfiles("local");
        application.run(args);
    }
}
