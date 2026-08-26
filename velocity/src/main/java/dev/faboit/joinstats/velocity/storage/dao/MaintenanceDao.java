package dev.faboit.joinstats.velocity.storage.dao;

import dev.faboit.joinstats.velocity.storage.Database;
import dev.faboit.joinstats.velocity.storage.WriteQueue;
import dev.faboit.joinstats.velocity.storage.model.Records;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Retention pruning, erasure, and the whole-database summary. */
public final class MaintenanceDao {

    /**
     * The tables retention can prune, mapped to the column holding their timestamp.
     *
     * <p>Kept as a fixed map rather than taking a table name from the caller: these strings end up
     * concatenated into SQL, and a table name is not something a prepared statement can bind.
     */
    private static final Map<String, String> PRUNABLE = Map.ofEntries(
            Map.entry("events", "js_events:at"),
            Map.entry("chat", "js_chat:at"),
            Map.entry("commands", "js_commands:at"),
            Map.entry("pings", "js_pings:at"),
            Map.entry("sessions", "js_sessions:started_at"),
            Map.entry("placeholder-history", "js_placeholder_history:at"),
            Map.entry("alerts", "js_alerts:at"),
            Map.entry("population-samples", "js_population:at"),
            Map.entry("population-breakdown", "js_population_breakdown:at"));

    private final Database database;
    private final WriteQueue writes;

    public MaintenanceDao(Database database, WriteQueue writes) {
        this.database = database;
        this.writes = writes;
    }

    /** Deletes rows older than {@code cutoff} from one of the prunable tables. */
    public CompletableFuture<Integer> prune(String what, long cutoff) {
        String target = PRUNABLE.get(what);
        if (target == null) {
            return CompletableFuture.completedFuture(0);
        }
        String[] parts = target.split(":");
        return writes.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + parts[0] + " WHERE " + parts[1] + " < ?")) {
                Database.bind(statement, cutoff);
                return statement.executeUpdate();
            }
        });
    }

    /** Deletes population rollups older than a cutoff, across every bucket width. */
    public CompletableFuture<Integer> pruneRollups(long cutoff) {
        return writes.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM js_population_rollup WHERE bucket < ?")) {
                Database.bind(statement, cutoff);
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Deletes rows for sessions that no longer exist.
     *
     * <p>Pruning {@code js_sessions} by age would otherwise strand the per-server visit rows that
     * point at them, which accumulate invisibly because nothing else ever reads an orphan.
     */
    public CompletableFuture<Integer> pruneOrphanedVisits() {
        return writes.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM js_session_servers
                     WHERE session_id NOT IN (SELECT id FROM js_sessions)
                    """)) {
                return statement.executeUpdate();
            }
        });
    }

    /** Deletes addresses no account references any more. */
    public CompletableFuture<Integer> pruneOrphanedAddresses() {
        return writes.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM js_addresses
                     WHERE address NOT IN (SELECT address FROM js_player_addresses)
                    """)) {
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Erases every trace of an account.
     *
     * <p>All of it in one transaction, so an interrupted erasure cannot leave a half-deleted
     * profile that still discloses the address history it was meant to remove.
     *
     * @return the number of rows deleted across all tables
     */
    public CompletableFuture<Integer> forget(UUID uuid) {
        return writes.submit(connection -> {
            String[] statements = {
                    "DELETE FROM js_players WHERE uuid = ?",
                    "DELETE FROM js_usernames WHERE uuid = ?",
                    "DELETE FROM js_player_addresses WHERE uuid = ?",
                    "DELETE FROM js_sessions WHERE uuid = ?",
                    "DELETE FROM js_session_servers WHERE uuid = ?",
                    "DELETE FROM js_player_servers WHERE uuid = ?",
                    "DELETE FROM js_activity_hourly WHERE uuid = ?",
                    "DELETE FROM js_activity_daily WHERE uuid = ?",
                    "DELETE FROM js_events WHERE uuid = ?",
                    "DELETE FROM js_chat WHERE uuid = ?",
                    "DELETE FROM js_commands WHERE uuid = ?",
                    "DELETE FROM js_placeholders WHERE uuid = ?",
                    "DELETE FROM js_placeholder_history WHERE uuid = ?",
                    "DELETE FROM js_alerts WHERE uuid = ?",
                    "DELETE FROM js_notes WHERE uuid = ?",
                    "DELETE FROM js_tags WHERE uuid = ?",
            };
            int deleted = 0;
            for (String sql : statements) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    Database.bind(statement, uuid);
                    deleted += statement.executeUpdate();
                }
            }
            // Addresses only this account ever used would otherwise survive the erasure.
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM js_addresses
                     WHERE address NOT IN (SELECT address FROM js_player_addresses)
                    """)) {
                deleted += statement.executeUpdate();
            }
            return deleted;
        });
    }

    /** Row counts per table, for {@code /joinstats status}. */
    public CompletableFuture<Map<String, Long>> tableSizes() {
        return database.query(connection -> {
            Map<String, Long> out = new LinkedHashMap<>();
            String[] tables = {"js_players", "js_usernames", "js_addresses", "js_player_addresses",
                    "js_sessions", "js_session_servers", "js_player_servers", "js_events",
                    "js_chat", "js_commands", "js_pings", "js_population",
                    "js_population_breakdown", "js_population_rollup", "js_placeholders",
                    "js_placeholder_history", "js_alerts", "js_notes", "js_tags",
                    "js_activity_hourly", "js_activity_daily"};
            for (String table : tables) {
                try (PreparedStatement statement =
                             connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                     ResultSet rows = statement.executeQuery()) {
                    out.put(table, rows.next() ? rows.getLong(1) : 0L);
                }
            }
            return out;
        });
    }

    /** The headline numbers shown by {@code /joinstats overview} and the metrics endpoint. */
    public CompletableFuture<Records.Overview> overview(long todayStart) {
        return database.query(connection -> {
            long players = scalar(connection, "SELECT COUNT(*) FROM js_players");
            long sessions = scalar(connection, "SELECT COUNT(*) FROM js_sessions");
            long events = scalar(connection, "SELECT COUNT(*) FROM js_events");
            long addresses = scalar(connection, "SELECT COUNT(*) FROM js_addresses");
            long playtime = scalar(connection, "SELECT COALESCE(SUM(playtime), 0) FROM js_players");
            long chat = scalar(connection, "SELECT COUNT(*) FROM js_chat");
            long commands = scalar(connection, "SELECT COUNT(*) FROM js_commands");
            long alerts = scalar(connection, "SELECT COUNT(*) FROM js_alerts");

            int peak = 0;
            long peakAt = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value, at FROM js_counters WHERE key = 'peak_online'");
                 ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    peak = rows.getInt(1);
                    peakAt = rows.getLong(2);
                }
            }

            long newToday;
            long activeToday;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM js_players WHERE first_seen >= ?")) {
                Database.bind(statement, todayStart);
                try (ResultSet rows = statement.executeQuery()) {
                    newToday = rows.next() ? rows.getLong(1) : 0L;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM js_players WHERE last_seen >= ?")) {
                Database.bind(statement, todayStart);
                try (ResultSet rows = statement.executeQuery()) {
                    activeToday = rows.next() ? rows.getLong(1) : 0L;
                }
            }

            long bytes = 0;
            try {
                bytes = Files.size(database.file());
            } catch (Exception ignored) {
                // A missing file here only affects a display value.
            }

            return new Records.Overview(players, sessions, events, addresses, playtime, chat,
                    commands, alerts, peak, peakAt, bytes, newToday, activeToday);
        });
    }

    private static long scalar(java.sql.Connection connection, String sql)
            throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    /** The identifiers {@link #prune} accepts. */
    public static java.util.Set<String> prunableTargets() {
        return PRUNABLE.keySet();
    }
}
