package dev.faboit.joinstats.velocity.storage.dao;

import dev.faboit.joinstats.velocity.storage.Database;
import dev.faboit.joinstats.velocity.storage.WriteQueue;
import dev.faboit.joinstats.velocity.storage.model.Records;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The latest and historical values of the PlaceholderAPI strings pulled from backends. */
public final class PlaceholderDao {

    private final Database database;
    private final WriteQueue writes;

    public PlaceholderDao(Database database, WriteQueue writes) {
        this.database = database;
        this.writes = writes;
    }

    /** Upserts the current value of one placeholder. */
    public void store(UUID uuid, String placeholder, String value, String server, long at) {
        writes.execute("""
                INSERT INTO js_placeholders (uuid, placeholder, value, server, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid, placeholder) DO UPDATE SET
                    value      = excluded.value,
                    server     = excluded.server,
                    updated_at = excluded.updated_at
                """, uuid, placeholder, value, server, at);
    }

    /** Appends a historical observation. */
    public void appendHistory(UUID uuid, String placeholder, String value, String server, long at) {
        writes.execute("""
                INSERT INTO js_placeholder_history (at, uuid, placeholder, value, server)
                VALUES (?, ?, ?, ?, ?)
                """, at, uuid, placeholder, value, server);
    }

    public CompletableFuture<List<Records.PlaceholderValue>> current(UUID uuid) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT placeholder, value, server, updated_at FROM js_placeholders
                     WHERE uuid = ? ORDER BY placeholder
                    """)) {
                Database.bind(statement, uuid);
                return Database.list(statement, rows -> new Records.PlaceholderValue(
                        rows.getString("placeholder"), rows.getString("value"),
                        rows.getString("server"), rows.getLong("updated_at")));
            }
        });
    }

    /**
     * Reads the stored values on the calling thread.
     *
     * <p>Used by the refresh loop to decide whether a newly resolved value actually changed,
     * which is what keeps {@code history-on-change-only} from writing a row every cycle for a
     * placeholder that never moves.
     */
    public Map<String, String> currentNow(UUID uuid) {
        return database.queryNow(connection -> {
            Map<String, String> out = new java.util.HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT placeholder, value FROM js_placeholders WHERE uuid = ?")) {
                Database.bind(statement, uuid);
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        out.put(rows.getString(1), rows.getString(2));
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<List<Records.PlaceholderPoint>> history(UUID uuid, String placeholder,
                                                                     int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT at, value, server FROM js_placeholder_history
                     WHERE uuid = ? AND placeholder = ? ORDER BY at DESC LIMIT ?
                    """)) {
                Database.bind(statement, uuid, placeholder, limit);
                return Database.list(statement, rows -> new Records.PlaceholderPoint(
                        rows.getLong("at"), rows.getString("value"), rows.getString("server")));
            }
        });
    }

    /** Ranks accounts by a placeholder whose values happen to be numeric. */
    public CompletableFuture<List<Records.Ranked>> rankNumeric(String placeholder, int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT ph.uuid AS uuid, p.username AS username,
                           CAST(REPLACE(REPLACE(ph.value, ',', ''), ' ', '') AS REAL) AS score
                      FROM js_placeholders ph
                      JOIN js_players p ON p.uuid = ph.uuid
                     WHERE ph.placeholder = ?
                       AND TRIM(COALESCE(ph.value, '')) <> ''
                       AND CAST(REPLACE(REPLACE(ph.value, ',', ''), ' ', '') AS REAL) <> 0
                     ORDER BY score DESC LIMIT ?
                    """)) {
                Database.bind(statement, placeholder, limit);
                List<Records.Ranked> out = new java.util.ArrayList<>();
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    int rank = 0;
                    while (rows.next()) {
                        double score = rows.getDouble("score");
                        out.add(new Records.Ranked(++rank,
                                UUID.fromString(rows.getString("uuid")),
                                rows.getString("username"), score,
                                score == Math.rint(score) ? String.valueOf((long) score)
                                        : String.format(java.util.Locale.ROOT, "%.2f", score)));
                    }
                }
                return out;
            }
        });
    }

    /** Every placeholder key we have ever stored, for tab-completion. */
    public CompletableFuture<List<String>> knownPlaceholders() {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT DISTINCT placeholder FROM js_placeholders ORDER BY placeholder")) {
                return Database.list(statement, rows -> rows.getString(1));
            }
        });
    }
}
