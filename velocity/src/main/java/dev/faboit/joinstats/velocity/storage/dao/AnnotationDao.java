package dev.faboit.joinstats.velocity.storage.dao;

import dev.faboit.joinstats.velocity.storage.Database;
import dev.faboit.joinstats.velocity.storage.WriteQueue;
import dev.faboit.joinstats.velocity.storage.model.Records;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Alerts, staff notes, tags, and the small set of proxy-wide counters. */
public final class AnnotationDao {

    private final Database database;
    private final WriteQueue writes;

    public AnnotationDao(Database database, WriteQueue writes) {
        this.database = database;
        this.writes = writes;
    }

    // ------------------------------------------------------------------ alerts

    public void raise(long at, String type, String severity, UUID uuid, String username,
                      String message, String data) {
        writes.execute("""
                INSERT INTO js_alerts (at, type, severity, uuid, username, message, data)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, at, type, severity, uuid, username, message, data);
    }

    public CompletableFuture<List<Records.Alert>> recentAlerts(String type, int limit, int offset) {
        return database.query(connection -> {
            String sql = type == null
                    ? "SELECT * FROM js_alerts ORDER BY at DESC LIMIT ? OFFSET ?"
                    : "SELECT * FROM js_alerts WHERE type = ? ORDER BY at DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (type == null) {
                    Database.bind(statement, limit, offset);
                } else {
                    Database.bind(statement, type, limit, offset);
                }
                return Database.list(statement, AnnotationDao::mapAlert);
            }
        });
    }

    public CompletableFuture<List<Records.Alert>> alertsFor(UUID uuid, int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_alerts WHERE uuid = ? ORDER BY at DESC LIMIT ?")) {
                Database.bind(statement, uuid, limit);
                return Database.list(statement, AnnotationDao::mapAlert);
            }
        });
    }

    public void acknowledge(long alertId) {
        writes.execute("UPDATE js_alerts SET acknowledged = 1 WHERE id = ?", alertId);
    }

    public void acknowledgeAll() {
        writes.execute("UPDATE js_alerts SET acknowledged = 1 WHERE acknowledged = 0");
    }

    /**
     * Counts alerts of one type raised for an account since an instant.
     *
     * <p>The detectors use this to avoid re-raising the same finding on every single join — an
     * alert stream that repeats itself is one nobody reads.
     */
    public CompletableFuture<Integer> countSince(String type, UUID uuid, long since) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM js_alerts WHERE type = ? AND uuid = ? AND at >= ?")) {
                Database.bind(statement, type, uuid, since);
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getInt(1) : 0;
                }
            }
        });
    }

    // ------------------------------------------------------------------ notes and tags

    public void addNote(long at, UUID uuid, String author, UUID authorUuid, String note) {
        writes.execute("""
                INSERT INTO js_notes (at, uuid, author, author_uuid, note) VALUES (?, ?, ?, ?, ?)
                """, at, uuid, author, authorUuid, note);
    }

    public void removeNote(long id) {
        writes.execute("DELETE FROM js_notes WHERE id = ?", id);
    }

    public CompletableFuture<List<Records.Note>> notesFor(UUID uuid, int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_notes WHERE uuid = ? ORDER BY at DESC LIMIT ?")) {
                Database.bind(statement, uuid, limit);
                return Database.list(statement, rows -> {
                    String author = rows.getString("author_uuid");
                    return new Records.Note(rows.getLong("id"), rows.getLong("at"),
                            UUID.fromString(rows.getString("uuid")), rows.getString("author"),
                            author == null ? null : UUID.fromString(author),
                            rows.getString("note"));
                });
            }
        });
    }

    public void addTag(UUID uuid, String tag, long at, String addedBy) {
        writes.execute("""
                INSERT INTO js_tags (uuid, tag, added_at, added_by) VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid, tag) DO UPDATE SET added_at = excluded.added_at,
                                                     added_by = excluded.added_by
                """, uuid, tag, at, addedBy);
    }

    public void removeTag(UUID uuid, String tag) {
        writes.execute("DELETE FROM js_tags WHERE uuid = ? AND tag = ?", uuid, tag);
    }

    public CompletableFuture<List<Records.Tag>> tagsFor(UUID uuid) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT tag, added_at, added_by FROM js_tags WHERE uuid = ? ORDER BY tag")) {
                Database.bind(statement, uuid);
                return Database.list(statement, rows -> new Records.Tag(
                        rows.getString("tag"), rows.getLong("added_at"),
                        rows.getString("added_by")));
            }
        });
    }

    public CompletableFuture<List<Records.Ranked>> playersWithTag(String tag, int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT t.uuid AS uuid, p.username AS username, t.added_at AS score
                      FROM js_tags t JOIN js_players p ON p.uuid = t.uuid
                     WHERE t.tag = ? ORDER BY t.added_at DESC LIMIT ?
                    """)) {
                Database.bind(statement, tag, limit);
                List<Records.Ranked> out = new java.util.ArrayList<>();
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    int rank = 0;
                    while (rows.next()) {
                        out.add(new Records.Ranked(++rank,
                                UUID.fromString(rows.getString("uuid")),
                                rows.getString("username"), rows.getDouble("score"),
                                dev.faboit.joinstats.velocity.util.Durations.ago(
                                        rows.getLong("score"), System.currentTimeMillis())));
                    }
                }
                return out;
            }
        });
    }

    public CompletableFuture<List<String>> knownTags() {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT DISTINCT tag FROM js_tags ORDER BY tag")) {
                return Database.list(statement, rows -> rows.getString(1));
            }
        });
    }

    // ------------------------------------------------------------------ counters

    /** Raises a counter to a new high-water mark, recording when it happened. */
    public void recordHighWater(String key, long value, long at, String detail) {
        writes.execute("""
                INSERT INTO js_counters (key, value, at, detail) VALUES (?, ?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET
                    value  = CASE WHEN excluded.value > js_counters.value
                                  THEN excluded.value ELSE js_counters.value END,
                    at     = CASE WHEN excluded.value > js_counters.value
                                  THEN excluded.at ELSE js_counters.at END,
                    detail = CASE WHEN excluded.value > js_counters.value
                                  THEN excluded.detail ELSE js_counters.detail END
                """, key, value, at, detail);
    }

    public CompletableFuture<Long> counter(String key) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM js_counters WHERE key = ?")) {
                Database.bind(statement, key);
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        });
    }

    static Records.Alert mapAlert(java.sql.ResultSet rows) throws java.sql.SQLException {
        String uuid = rows.getString("uuid");
        return new Records.Alert(rows.getLong("id"), rows.getLong("at"), rows.getString("type"),
                rows.getString("severity"), uuid == null ? null : UUID.fromString(uuid),
                rows.getString("username"), rows.getString("message"), rows.getString("data"),
                rows.getInt("acknowledged") != 0);
    }
}
