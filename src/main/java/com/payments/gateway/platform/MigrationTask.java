package com.payments.gateway.platform;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The one-off deploy migration task (ADR-026): a context with only a DataSource and Flyway, so it needs the migration
 * role's credentials and nothing else (no data keys, PSP credentials or web server). Not a component, so the
 * application context never picks it up.
 */
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class MigrationTask {

    public static boolean requested(Map<String, String> environment) {
        return Boolean.parseBoolean(environment.get("PG_MIGRATE_ONLY"));
    }

    /** Applies the migrations and returns the process exit code. */
    public static int run(String... args) {
        SpringApplication application = new SpringApplication(MigrationTask.class);
        application.setMainApplicationClass(MigrationTask.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        String[] alwaysMigrate = Stream.concat(Stream.of("--spring.flyway.enabled=true"), Arrays.stream(args))
                .toArray(String[]::new);
        return SpringApplication.exit(application.run(alwaysMigrate));
    }

    @Bean
    FlywayMigrationStrategy verifiedTlsInProduction(Environment environment) {
        return flyway -> {
            String url = environment.getProperty("spring.flyway.url",
                    environment.getProperty("spring.datasource.url", ""));
            if (environment.matchesProfiles("prod") && !ProductionConfigurationGuard.verifiesServerCertificate(url)) {
                throw new IllegalStateException("Unsafe production configuration: the migration database URL must use "
                        + "sslmode=verify-full (ADR-026)");
            }
            flyway.migrate();
        };
    }
}
