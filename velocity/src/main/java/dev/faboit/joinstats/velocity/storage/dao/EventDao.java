package dev.faboit.joinstats.velocity.storage.dao;

import dev.faboit.joinstats.velocity.storage.Database;
import dev.faboit.joinstats.velocity.storage.WriteQueue;
import dev.faboit.joinstats.velocity.storage.model.Records;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The append-only logs: generic events, chat, commands and server-list pings. */
public final class EventDao {

    private final Database database;
    private final WriteQueue writes;

    public EventDao(Database database, WriteQueue writes) {
        this.database = database;
        this.writes = writes;
    }

    /** Appends one row to the generic event log. */
    public void record(long at, String type, UUID uuid, String username, String address,
                       String server, String fromServer, Long sessionId, String detail,
                       String data) {
        writes.execute("""
                INSERT INTO js_events (at, type, uuid, username, address, server, from_server,
                        session_id, detail, data)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, at, type, uuid, username, address, server, fromServer, sessionId, detail, data);
    }

    public void recordChat(long at, UUID uuid, String username, String server, String message,
                           int length, boolean cancelled) {
        writes.execute("""
                INSERT INTO js_chat (at, uuid, username, server, message, length, cancelled)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, at, uuid, username, server, message, length, cancelled);
    }

    public void recordCommand(long at, UUID uuid, String username, String server, String command,
                              String arguments, boolean cancelled) {
        writes.execute("""
                INSERT INTO js_commands (at, uuid, username, server, command, arguments, cancelled)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, at, uuid, username, server, command, arguments, cancelled);
    }

    public void recordPing(long at, String address, String virtualHost, int protocol,
                           String versionName) {
        writes.execute("""
                INSERT INTO js_pings (at, address, virtual_host, protocol, version_name)
                VALUES (?, ?, ?, ?, ?)
                """, at, address, virtualHost, protocol, versionName);
    }

    // ------------------------------------------------------------------ reads

    public CompletableFuture<List<Records.Event>> forPlayer(UUID uuid, int limit, int offset) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_events WHERE uuid = ? ORDER BY at DESC LIMIT ? OFFSET ?")) {
                Database.bind(statement, uuid, limit, offset);
                return Database.list(statement, EventDao::mapEvent);
            }
        });
    }

    public CompletableFuture<List<Records.Event>> recent(String type, int limit, int offset) {
        return database.query(connection -> {
            String sql = type == null
                    ? "SELECT * FROM js_events ORDER BY at DESC LIMIT ? OFFSET ?"
                    : "SELECT * FROM js_events WHERE type = ? ORDER BY at DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (type == null) {
                    Database.bind(statement, limit, offset);
                } else {
                    Database.bind(statement, type, limit, offset);
                }
                return Database.list(statement, EventDao::mapEvent);
            }
        });
    }

    public CompletableFuture<List<Records.ChatLine>> chatOf(UUID uuid, int limit, int offset) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_chat WHERE uuid = ? ORDER BY at DESC LIMIT ? OFFSET ?")) {
                Database.bind(statement, uuid, limit, offset);
                return Database.list(statement, rows -> new Records.ChatLine(
                        rows.getLong("id"), rows.getLong("at"),
                        UUID.fromString(rows.getString("uuid")), rows.getString("username"),
                        rows.getString("server"), rows.getString("message"), rows.getInt("length"),
                        rows.getInt("cancelled") != 0));
            }
        });
    }

    public CompletableFuture<List<Records.CommandLine>> commandsOf(UUID uuid, int limit,
                                                                   int offset) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_commands WHERE uuid = ? ORDER BY at DESC LIMIT ? OFFSET ?")) {
                Database.bind(statement, uuid, limit, offset);
                return Database.list(statement, rows -> new Records.CommandLine(
                        rows.getLong("id"), rows.getLong("at"),
                        rows.getString("uuid") == null ? null
                                : UUID.fromString(rows.getString("uuid")),
                        rows.getString("username"), rows.getString("server"),
                        rows.getString("command"), rows.getString("arguments"),
                        rows.getInt("cancelled") != 0));
            }
        });
    }

    /** The commands run most often across the network. */
    public CompletableFuture<List<Records.Tally>> topCommands(int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT command AS k, COUNT(*) AS c FROM js_commands "
                            + "GROUP BY command ORDER BY c DESC LIMIT ?")) {
                Database.bind(statement, limit);
                return Database.list(statement, rows -> new Records.Tally(
                        rows.getString("k"), rows.getLong("c"), rows.getString("k")));
            }
        });
    }

    /** The hostnames players actually connect through, from the ping log. */
    public CompletableFuture<List<Records.Tally>> topVirtualHosts(int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(virtual_host, 'direct') AS k, COUNT(*) AS c
                      FROM js_sessions GROUP BY k ORDER BY c DESC LIMIT ?
                    """)) {
                Database.bind(statement, limit);
                return Database.list(statement, rows -> new Records.Tally(
                        rows.getString("k"), rows.getLong("c"), rows.getString("k")));
            }
        });
    }

    public CompletableFuture<List<Records.PingEntry>> recentPings(int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_pings ORDER BY at DESC LIMIT ?")) {
                Database.bind(statement, limit);
                return Database.list(statement, rows -> new Records.PingEntry(
                        rows.getLong("id"), rows.getLong("at"), rows.getString("address"),
                        rows.getString("virtual_host"), rows.getInt("protocol"),
                        rows.getString("version_name")));
            }
        });
    }

    static Records.Event mapEvent(java.sql.ResultSet rows) throws java.sql.SQLException {
        String uuid = rows.getString("uuid");
        long sessionId = rows.getLong("session_id");
        return new Records.Event(
                rows.getLong("id"), rows.getLong("at"), rows.getString("type"),
                uuid == null ? null : UUID.fromString(uuid), rows.getString("username"),
                rows.getString("address"), rows.getString("server"), rows.getString("from_server"),
                rows.wasNull() ? null : sessionId, rows.getString("detail"), rows.getString("data"));
    }
}
