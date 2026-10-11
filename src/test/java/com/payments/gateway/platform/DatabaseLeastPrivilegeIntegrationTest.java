package com.payments.gateway.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payments.gateway.support.EmbeddedPostgresSupport;
import com.payments.gateway.support.IntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-026: the application role granted by the Flyway {@code afterMigrate} callback can read and write data, but can
 * never change the schema, truncate tables, rewrite append-only history, or touch the migration history.
 */
class DatabaseLeastPrivilegeIntegrationTest extends IntegrationTest {

    private static final String APP_ROLE = "gateway_app_test";

    @BeforeEach
    void grantTheApplicationRole() throws IOException {
        jdbc.sql("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gateway_app_test') THEN
                        CREATE ROLE gateway_app_test LOGIN;
                    END IF;
                END $$""").update();
        try (InputStream in = getClass().getResourceAsStream("/db/migration/afterMigrate.sql")) {
            assertThat(in).as("afterMigrate.sql on the classpath").isNotNull();
            String callback = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${app_role}", APP_ROLE);
            jdbc.sql(callback).update();
        }
        createMerchant(ALPHA);
        payAndSucceed(createMerchant(ALPHA), 10_000);
    }

    @Test
    void theApplicationRoleCanReadAndWriteData() throws SQLException {
        try (Connection app = connectAsApplication(); Statement sql = app.createStatement()) {
            assertThat(sql.executeQuery("SELECT count(*) FROM payments").next()).isTrue();
            assertThat(sql.executeUpdate("UPDATE merchants SET name = name")).isEqualTo(2);
            assertThat(sql.executeUpdate("""
                    INSERT INTO audit_log (actor_type, actor_id, action, resource_type, resource_id, occurred_at)
                    VALUES ('system', 'test', 'test.action', 'test', 'test', now())""")).isEqualTo(1);
            assertThat(sql.executeUpdate("DELETE FROM idempotency_records WHERE false")).isZero();
            assertThat(sql.executeUpdate("DELETE FROM routing_decisions WHERE false")).isZero();
            assertThat(sql.executeUpdate("UPDATE billing_events SET service_fee = service_fee WHERE false")).isZero();
            assertThat(sql.executeUpdate("UPDATE billing_accounts SET next_month = next_month WHERE false")).isZero();
            assertThat(sql.executeUpdate("UPDATE billing_series SET last_value = last_value WHERE false")).isZero();
            assertThat(sql.executeUpdate("UPDATE dispute_evidence_files SET provider_document_id = 'doc_1', "
                    + "content_key_enc = content_key_enc WHERE false")).as("the two columns that may change").isZero();
        }
    }

    @Test
    void theApplicationRoleCannotChangeTheSchemaOrRewriteHistory() throws SQLException {
        try (Connection app = connectAsApplication()) {
            app.setAutoCommit(true);
            for (String forbidden : new String[] {
                    "CREATE TABLE smuggled (id int)",
                    "ALTER TABLE payments ADD COLUMN smuggled int",
                    "DROP TABLE refunds",
                    "TRUNCATE payments",
                    "CREATE INDEX smuggled ON payments (id)",
                    "UPDATE audit_log SET action = 'forged'",
                    "DELETE FROM audit_log",
                    "UPDATE ledger_entries SET amount = amount + 1",
                    "DELETE FROM ledger_transactions",
                    "UPDATE fx_conversions SET settled_amount = settled_amount + 1",
                    "DELETE FROM fx_conversions",
                    "UPDATE provider_fee_schedules SET fixed_amount = fixed_amount + 1",
                    "DELETE FROM provider_fee_schedules",
                    "UPDATE routing_decisions SET chosen_provider = 'forged'",
                    "UPDATE billing_plans SET monthly_minimum = 0",
                    "DELETE FROM billing_plan_prices",
                    "DELETE FROM billing_events",
                    "UPDATE billing_events SET recognized_at = now()",
                    "UPDATE billing_accounts SET enrolled_at = now()",
                    "DELETE FROM billing_accounts",
                    "UPDATE billing_invoices SET total = total + 1",
                    "DELETE FROM billing_invoice_lines",
                    "DELETE FROM billing_event_assignments",
                    "UPDATE billing_credit_notes SET reason = 'forged'",
                    "DELETE FROM billing_credit_lines",
                    "DELETE FROM billing_series",
                    "UPDATE billing_series SET financial_year = 2027",
                    "UPDATE billing_series SET series = 'CN'",
                    "DELETE FROM payment_transitions",
                    "DELETE FROM dispute_evidence_files",
                    "UPDATE dispute_evidence_files SET content_enc = content_enc",
                    "UPDATE dispute_evidence_files SET sha256 = sha256",
                    "SELECT * FROM flyway_schema_history",
                    "DELETE FROM flyway_schema_history"}) {
                try (Statement sql = app.createStatement()) {
                    assertThatThrownBy(() -> sql.execute(forbidden))
                            .as(forbidden)
                            .isInstanceOf(SQLException.class)
                            .satisfies(e -> assertThat(((SQLException) e).getSQLState())
                                    .as("%s must fail with insufficient_privilege, not a later check", forbidden)
                                    .isIn("42501", "42809"));
                }
            }
        }
        assertThat(count("SELECT count(*) FROM ledger_entries")).isPositive();
    }

    private static Connection connectAsApplication() throws SQLException {
        Connection connection = DriverManager.getConnection(EmbeddedPostgresSupport.jdbcUrlAs(APP_ROLE));
        try (Statement sql = connection.createStatement();
                var rs = sql.executeQuery("SELECT current_user")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(APP_ROLE);
        }
        return connection;
    }
}
