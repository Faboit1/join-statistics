package dev.faboit.joinstats.velocity.storage.dao;

import dev.faboit.joinstats.velocity.storage.Database;
import dev.faboit.joinstats.velocity.storage.WriteQueue;
import dev.faboit.joinstats.velocity.storage.model.PlayerProfile;
import dev.faboit.joinstats.velocity.storage.model.Records;
import dev.faboit.joinstats.velocity.util.Durations;
import dev.faboit.joinstats.velocity.util.Names;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Reads and writes the per-account profile, its name history and the leaderboards. */
public final class PlayerDao {

    private final Database database;
    private final WriteQueue writes;

    public PlayerDao(Database database, WriteQueue writes) {
        this.database = database;
        this.writes = writes;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Records that an account just connected.
     *
     * <p>{@code first_seen} and {@code first_protocol} are written only by the INSERT arm, so a
     * returning player never has their history rewritten by a later login.
     */
    public void recordLogin(UUID uuid, String username, long now, String address,
                            String countryCode, String country, String city, int protocol,
                            String versionName, String brand, String locale, boolean onlineMode) {
        writes.execute("""
                INSERT INTO js_players (uuid, username, username_key, first_seen, last_seen,
                        connections, last_address, last_country, last_country_code, last_city,
                        first_protocol, last_protocol, last_version, last_brand, last_locale,
                        online_mode)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username          = excluded.username,
                    username_key      = excluded.username_key,
                    last_seen         = excluded.last_seen,
                    connections       = js_players.connections + 1,
                    last_address      = excluded.last_address,
                    last_country      = COALESCE(excluded.last_country, js_players.last_country),
                    last_country_code = COALESCE(excluded.last_country_code, js_players.last_country_code),
                    last_city         = COALESCE(excluded.last_city, js_players.last_city),
                    last_protocol     = excluded.last_protocol,
                    last_version      = excluded.last_version,
                    last_brand        = COALESCE(excluded.last_brand, js_players.last_brand),
                    last_locale       = COALESCE(excluded.last_locale, js_players.last_locale),
                    online_mode       = excluded.online_mode
                """,
                uuid, username, Names.key(username), now, now, address, country, countryCode, city,
                protocol, protocol, versionName, brand, locale, onlineMode);

        writes.execute("""
                INSERT INTO js_usernames (uuid, username, username_key, first_seen, last_seen, connections)
                VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(uuid, username_key) DO UPDATE SET
                    username    = excluded.username,
                    last_seen   = excluded.last_seen,
                    connections = js_usernames.connections + 1
                """,
                uuid, username, Names.key(username), now, now);
    }

    /** Updates only the fields that can change while a player is already connected. */
    public void recordClientDetails(UUID uuid, String brand, String locale) {
        writes.execute("""
                UPDATE js_players
                   SET last_brand  = COALESCE(?, last_brand),
                       last_locale = COALESCE(?, last_locale)
                 WHERE uuid = ?
                """, brand, locale, uuid);
    }

    /** Folds a finished session's totals into the profile. */
    public void applySessionEnd(UUID uuid, long endedAt, long duration, long idleTime,
                                String lastServer) {
        writes.execute("""
                UPDATE js_players
                   SET last_seen       = MAX(last_seen, ?),
                       last_quit       = ?,
                       playtime        = playtime + ?,
                       idle_time       = idle_time + ?,
                       sessions        = sessions + 1,
                       longest_session = MAX(longest_session, ?),
                       last_server     = COALESCE(?, last_server)
                 WHERE uuid = ?
                """, endedAt, endedAt, duration, idleTime, duration, lastServer, uuid);
    }

/**
     * Records that the account was seen, without counting a session.
     *
     * <p>Used for a session too short to be meaningful. It still happened — {@code last_seen} and
     * {@code last_quit} must move — but folding it into the session count and playtime is exactly
     * what {@code sessions.minimum-meaningful-session} exists to prevent: a run of failed
     * handshakes would otherwise halve a player's apparent average session length.
     */
    public void touchSeen(UUID uuid, long at, String lastServer) {
        writes.execute("""
                UPDATE js_players
                   SET last_seen   = MAX(last_seen, ?),
                       last_quit   = ?,
                       last_server = COALESCE(?, last_server)
                 WHERE uuid = ?
                """, at, at, lastServer, uuid);
    }

    /** Bumps one of the small per-account counters. */
    public void increment(UUID uuid, Counter counter, int amount) {
        writes.execute("UPDATE js_players SET " + counter.column + " = " + counter.column
                + " + ? WHERE uuid = ?", amount, uuid);
    }

    /** Folds a latency sample into the running average and the best/worst pair. */
    public void recordPing(UUID uuid, int millis) {
        if (millis < 0) {
            return;
        }
        writes.execute("""
                UPDATE js_players
                   SET ping_total   = ping_total + ?,
                       ping_samples = ping_samples + 1,
                       ping_best    = CASE WHEN ping_samples = 0 THEN ? ELSE MIN(ping_best, ?) END,
                       ping_worst   = MAX(ping_worst, ?)
                 WHERE uuid = ?
                """, millis, millis, millis, millis, uuid);
    }

    /** Updates the last known location without waiting for the next login. */
    public void recordLocation(UUID uuid, String country, String countryCode, String city) {
        writes.execute("""
                UPDATE js_players
                   SET last_country      = COALESCE(?, last_country),
                       last_country_code = COALESCE(?, last_country_code),
                       last_city         = COALESCE(?, last_city)
                 WHERE uuid = ?
                """, country, countryCode, city, uuid);
    }

    // ------------------------------------------------------------------ reads

    public CompletableFuture<Optional<PlayerProfile>> find(UUID uuid) {
        return database.query(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT * FROM js_players WHERE uuid = ?")) {
                Database.bind(statement, uuid);
                List<PlayerProfile> rows = Database.list(statement, PlayerDao::mapProfile);
                return rows.isEmpty() ? Optional.<PlayerProfile>empty() : Optional.of(rows.get(0));
            }
        });
    }

    public CompletableFuture<Optional<PlayerProfile>> findByName(String username) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_players WHERE username_key = ? LIMIT 1")) {
                Database.bind(statement, Names.key(username));
                List<PlayerProfile> rows = Database.list(statement, PlayerDao::mapProfile);
                if (!rows.isEmpty()) {
                    return Optional.of(rows.get(0));
                }
            }
            // Fall back to the name history, so looking up someone by an old name still works.
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.* FROM js_players p
                      JOIN js_usernames u ON u.uuid = p.uuid
                     WHERE u.username_key = ?
                     ORDER BY u.last_seen DESC
                     LIMIT 1
                    """)) {
                Database.bind(statement, Names.key(username));
                List<PlayerProfile> rows = Database.list(statement, PlayerDao::mapProfile);
                return rows.isEmpty() ? Optional.<PlayerProfile>empty() : Optional.of(rows.get(0));
            }
        });
    }

    /** Resolves either a UUID or a (current or historical) username. */
    public CompletableFuture<Optional<PlayerProfile>> resolve(String query) {
        UUID uuid = Names.parseUuid(query);
        return uuid != null ? find(uuid) : findByName(query);
    }

    public CompletableFuture<List<Records.NameHistoryEntry>> nameHistory(UUID uuid) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT username, first_seen, last_seen, connections
                      FROM js_usernames WHERE uuid = ? ORDER BY last_seen DESC
                    """)) {
                Database.bind(statement, uuid);
                return Database.list(statement, rows -> new Records.NameHistoryEntry(
                        rows.getString("username"), rows.getLong("first_seen"),
                        rows.getLong("last_seen"), rows.getInt("connections")));
            }
        });
    }

    /** Names matching a prefix, for tab-completion of offline players. */
    public CompletableFuture<List<String>> completeNames(String prefix, int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT username FROM js_players
                     WHERE username_key LIKE ? ESCAPE '\\'
                     ORDER BY last_seen DESC LIMIT ?
                    """)) {
                Database.bind(statement, escapeLike(prefix.toLowerCase(Locale.ROOT)) + "%", limit);
                return Database.list(statement, rows -> rows.getString(1));
            }
        });
    }

    /** Free-text search across current and historical names. */
    public CompletableFuture<List<PlayerProfile>> search(String query, int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT p.* FROM js_players p
                      LEFT JOIN js_usernames u ON u.uuid = p.uuid
                     WHERE p.username_key LIKE ? ESCAPE '\\'
                        OR u.username_key LIKE ? ESCAPE '\\'
                     ORDER BY p.last_seen DESC LIMIT ?
                    """)) {
                String pattern = "%" + escapeLike(query.toLowerCase(Locale.ROOT)) + "%";
                Database.bind(statement, pattern, pattern, limit);
                return Database.list(statement, PlayerDao::mapProfile);
            }
        });
    }

    /** A leaderboard over one of the profile's numeric columns. */
    public CompletableFuture<List<Records.Ranked>> top(Metric metric, int limit, int offset) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, username, " + metric.expression + " AS score FROM js_players "
                            + "WHERE " + metric.filter + " ORDER BY score DESC LIMIT ? OFFSET ?")) {
                Database.bind(statement, limit, offset);
                List<Records.Ranked> out = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    int rank = offset;
                    while (rows.next()) {
                        double score = rows.getDouble("score");
                        out.add(new Records.Ranked(++rank,
                                UUID.fromString(rows.getString("uuid")),
                                rows.getString("username"), score, metric.render(score)));
                    }
                }
                return out;
            }
        });
    }

    /** Groups the population by one of the descriptive profile columns. */
    public CompletableFuture<List<Records.Tally>> tally(String column, int limit) {
        String safeColumn = switch (column) {
            case "country" -> "last_country";
            case "version" -> "last_version";
            case "brand" -> "last_brand";
            case "locale" -> "last_locale";
            case "server" -> "last_server";
            default -> throw new IllegalArgumentException("Unknown grouping: " + column);
        };
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(" + safeColumn + ", 'unknown') AS k, COUNT(*) AS c "
                            + "FROM js_players GROUP BY k ORDER BY c DESC LIMIT ?")) {
                Database.bind(statement, limit);
                return Database.list(statement, rows -> new Records.Tally(
                        rows.getString("k"), rows.getLong("c"), rows.getString("k")));
            }
        });
    }

    /** Accounts that have not connected since the given instant. */
    public CompletableFuture<List<PlayerProfile>> inactiveSince(long cutoff, int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_players WHERE last_seen < ? ORDER BY last_seen ASC LIMIT ?")) {
                Database.bind(statement, cutoff, limit);
                return Database.list(statement, PlayerDao::mapProfile);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Escapes the LIKE metacharacters so a name containing {@code _} is not a wildcard. */
    static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static PlayerProfile mapProfile(ResultSet rows) throws SQLException {
        return new PlayerProfile(
                UUID.fromString(rows.getString("uuid")),
                rows.getString("username"),
                rows.getLong("first_seen"),
                rows.getLong("last_seen"),
                rows.getLong("last_quit"),
                rows.getLong("playtime"),
                rows.getLong("idle_time"),
                rows.getInt("sessions"),
                rows.getInt("connections"),
                rows.getLong("longest_session"),
                rows.getInt("kicks"),
                rows.getInt("chat_messages"),
                rows.getInt("commands"),
                rows.getInt("server_switches"),
                rows.getString("last_address"),
                rows.getString("last_country"),
                rows.getString("last_country_code"),
                rows.getString("last_city"),
                rows.getString("last_server"),
                rows.getInt("first_protocol"),
                rows.getInt("last_protocol"),
                rows.getString("last_version"),
                rows.getString("last_brand"),
                rows.getString("last_locale"),
                rows.getInt("online_mode") != 0,
                rows.getLong("ping_total"),
                rows.getLong("ping_samples"),
                rows.getInt("ping_best"),
                rows.getInt("ping_worst"));
    }

    /** The counters {@link #increment} can bump, so no caller can inject a column name. */
    public enum Counter {
        KICKS("kicks"),
        CHAT_MESSAGES("chat_messages"),
        COMMANDS("commands"),
        SERVER_SWITCHES("server_switches");

        private final String column;

        Counter(String column) {
            this.column = column;
        }
    }

    /** The leaderboards {@code /joinstats top} offers. */
    public enum Metric {
        PLAYTIME("playtime", "playtime > 0", true),
        SESSIONS("sessions", "sessions > 0", false),
        CONNECTIONS("connections", "connections > 0", false),
        LONGEST_SESSION("longest_session", "longest_session > 0", true),
        AVERAGE_SESSION("CASE WHEN sessions > 0 THEN playtime / sessions ELSE 0 END",
                "sessions > 0", true),
        CHAT("chat_messages", "chat_messages > 0", false),
        COMMANDS("commands", "commands > 0", false),
        KICKS("kicks", "kicks > 0", false),
        SWITCHES("server_switches", "server_switches > 0", false),
        IDLE("idle_time", "idle_time > 0", true),
        PING("CASE WHEN ping_samples > 0 THEN -(ping_total / ping_samples) ELSE -999999 END",
                "ping_samples > 0", false),
        FIRST_SEEN("-first_seen", "first_seen > 0", false);

        private final String expression;
        private final String filter;
        private final boolean duration;

        Metric(String expression, String filter, boolean duration) {
            this.expression = expression;
            this.filter = filter;
            this.duration = duration;
        }

        String render(double score) {
            if (duration) {
                return Durations.format((long) score);
            }
            if (this == PING) {
                return Math.round(-score) + "ms";
            }
            if (this == FIRST_SEEN) {
                return Durations.ago((long) -score, System.currentTimeMillis());
            }
            return String.valueOf(Math.round(score));
        }

        public String display() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }

        public static Metric of(String raw) {
            for (Metric metric : values()) {
                if (metric.name().equalsIgnoreCase(raw)
                        || metric.display().equalsIgnoreCase(raw)) {
                    return metric;
                }
            }
            return null;
        }
    }
}
