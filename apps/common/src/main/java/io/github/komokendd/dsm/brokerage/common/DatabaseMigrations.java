package io.github.komokendd.dsm.brokerage.common;

import java.sql.DriverManager;
import java.util.Map;
import org.flywaydb.core.Flyway;

/** Runs in an init container and exits before any application beans or listeners start. */
public final class DatabaseMigrations {
    private DatabaseMigrations() { }

    public static boolean runIfRequested() {
        Map<String, String> env = System.getenv();
        if (!"true".equals(env.get("BROKERAGE_DEMO_MIGRATE_ONLY"))) return false;
        String url = required(env, "DATABASE_URL");
        String username = required(env, "DATABASE_USERNAME");
        String password = required(env, "DATABASE_PASSWORD");
        String schema = identifier(required(env, "DATABASE_SCHEMA"));
        String runtimeRole = identifier(required(env, "DATABASE_RUNTIME_ROLE"));
        String history = "flyway_schema_history_" + schema;
        Flyway.configure().dataSource(url, username, password)
                .schemas(schema).defaultSchema(schema).createSchemas(false)
                .table(history).baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();
        try (var connection = DriverManager.getConnection(url, username, password);
                var statement = connection.createStatement()) {
            // Default privileges cover future business tables, but runtime code cannot alter migration history.
            statement.execute("REVOKE ALL ON TABLE \"" + schema + "\".\"" + history + "\" FROM \"" + runtimeRole + "\"");
            if ("order_service".equals(schema)) {
                statement.execute("REVOKE ALL ON TABLE order_service.epoch_budget FROM \"" + runtimeRole + "\"");
                statement.execute("GRANT SELECT ON TABLE order_service.epoch_budget TO \"" + runtimeRole + "\"");
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Could not restrict migration history privileges", exception);
        }
        return true;
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isEmpty()) throw new IllegalStateException("Required migration setting is missing: " + key);
        return value;
    }

    private static String identifier(String value) {
        if (!value.matches("[a-z][a-z0-9_]{0,50}")) throw new IllegalArgumentException("Invalid database identifier");
        return value;
    }
}
