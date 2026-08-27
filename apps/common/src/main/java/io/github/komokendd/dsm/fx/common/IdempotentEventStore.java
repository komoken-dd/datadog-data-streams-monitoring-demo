package io.github.komokendd.dsm.fx.common;

import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

public final class IdempotentEventStore {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private final JdbcTemplate jdbc;
    private final String table;

    public IdempotentEventStore(JdbcTemplate jdbc, String schema) {
        if (!IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Unsafe PostgreSQL schema name: " + schema);
        }
        this.jdbc = jdbc;
        this.table = schema + ".processed_events";
    }

    public boolean claim(String eventId) {
        return jdbc.update(
                        "insert into " + table + " (event_id, processed_at) values (?, now()) on conflict do nothing",
                        eventId)
                == 1;
    }
}

