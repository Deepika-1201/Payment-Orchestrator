package com.payments.gateway.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payments.gateway.support.EmbeddedPostgresSupport;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** ADR-026: the deploy migration task migrates and grants with database settings alone, and checks TLS in prod. */
class MigrationTaskIntegrationTest {

    private static final String DATABASE = "migration_task_test";
    private static final String APP_ROLE = "gateway_app_task_test";

    @BeforeEach
    void freshDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(EmbeddedPostgresSupport.jdbcUrl());
                Statement sql = admin.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            sql.execute("CREATE DATABASE " + DATABASE);
            sql.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_ROLE + "') THEN "
                    + "CREATE ROLE " + APP_ROLE + " LOGIN; END IF; END $$");
        }
    }

    @Test
    void migratesAndGrantsWithNothingButTheDatabaseSettings() throws SQLException, IOException {
        int migrations = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql").length;

        int exitCode = MigrationTask.run("--spring.datasource.url=" + url(),
                "--spring.flyway.placeholders.app_role=" + APP_ROLE);

        assertThat(exitCode).isZero();
        try (Connection db = DriverManager.getConnection(url()); Statement sql = db.createStatement()) {
            assertThat(query(sql, "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL"))
                    .isEqualTo(String.valueOf(migrations));
            assertThat(query(sql, "SELECT has_table_privilege('" + APP_ROLE + "', 'payments', 'INSERT')"))
                    .isEqualTo("t");
            assertThat(query(sql, "SELECT has_table_privilege('" + APP_ROLE + "', 'audit_log', 'UPDATE')"))
                    .isEqualTo("f");
        }
    }

    @Test
    void refusesAProductionDatabaseUrlWithoutCertificateVerification() throws SQLException {
        assertThatThrownBy(() -> MigrationTask.run("--spring.profiles.active=prod",
                "--spring.datasource.url=" + url() + "&sslmode=disable"))
                .hasStackTraceContaining("sslmode=verify-full");

        try (Connection db = DriverManager.getConnection(url()); Statement sql = db.createStatement()) {
            assertThat(query(sql, "SELECT to_regclass('public.flyway_schema_history') IS NULL")).isEqualTo("t");
        }
    }

    private static String url() {
        return EmbeddedPostgresSupport.jdbcUrlFor("postgres", DATABASE);
    }

    private static String query(Statement sql, String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
