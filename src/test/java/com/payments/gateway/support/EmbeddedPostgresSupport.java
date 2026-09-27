package com.payments.gateway.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;

/** One embedded PostgreSQL per test JVM (no Docker required). */
public final class EmbeddedPostgresSupport {

    private static EmbeddedPostgres postgres;

    private EmbeddedPostgresSupport() {
    }

    public static synchronized String jdbcUrl() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // best effort on JVM exit
                }
            }));
        }
        return postgres.getJdbcUrl("postgres", "postgres");
    }

    /** The same database, connecting as {@code user} (the embedded cluster trusts local connections). */
    public static synchronized String jdbcUrlAs(String user) {
        return jdbcUrlFor(user, "postgres");
    }

    public static synchronized String jdbcUrlFor(String user, String database) {
        jdbcUrl();
        return postgres.getJdbcUrl(user, database);
    }
}
