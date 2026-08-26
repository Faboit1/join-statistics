package dev.faboit.joinstats.velocity.storage.dao;

import dev.faboit.joinstats.velocity.storage.Database;
import dev.faboit.joinstats.velocity.storage.WriteQueue;
import dev.faboit.joinstats.velocity.storage.model.Records;
import dev.faboit.joinstats.velocity.storage.model.SessionRecord;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Session rows, per-server visits, and the activity heatmaps derived from them. */
public final class SessionDao {

    private final Database database;
    private final WriteQueue writes;

    public SessionDao(Database database, WriteQueue writes) {
        this.database = database;
        this.writes = writes;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Inserts an open session and returns its id.
     *
     * <p>Written immediately rather than at disconnect so that a proxy crash still leaves a
     * record of who was online; {@link #recoverOpenSessions} closes those on the next start.
     */
    public CompletableFuture<Long> open(UUID uuid, String username, long startedAt, String address,
                                        String subnet, String countryCode, String city,
                                        int protocol, String versionName, String brand,
                                        String locale, String virtualHost, boolean onlineMode,
                                        String firstServer) {
        return writes.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO js_sessions (uuid, username, started_at, heartbeat_at, open,
                            connections, address, subnet, country_code, city, protocol,
                            version_name, brand, locale, virtual_host, online_mode, first_server,
                            last_server)
                    VALUES (?, ?, ?, ?, 1, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(statement, uuid, username, startedAt, startedAt, address, subnet,
                        countryCode, city, protocol, versionName, brand, locale, virtualHost,
                        onlineMode, firstServer, firstServer);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            }
        });
    }

    /** Marks a lingering session as live again after a reconnect inside the grace window. */
    public void resume(long sessionId, long at, String address, String subnet, int protocol,
                       String virtualHost) {
        writes.execute("""
                UPDATE js_sessions
                   SET open         = 1,
                       ended_at     = 0,
                       heartbeat_at = ?,
                       connections  = connections + 1,
                       address      = COALESCE(?, address),
                       subnet       = COALESCE(?, subnet),
                       protocol     = CASE WHEN ? > 0 THEN ? ELSE protocol END,
                       virtual_host = COALESCE(?, virtual_host)
                 WHERE id = ?
                """, at, address, subnet, protocol, protocol, virtualHost, sessionId);
    }

    /** Keeps an open session's last-known-good timestamp fresh. */
    public void heartbeat(long sessionId, long at, long duration, long idleTime, long pingTotal,
                          long pingSamples) {
        writes.execute("""
                UPDATE js_sessions
                   SET heartbeat_at = ?, duration = ?, idle_time = ?,
                       ping_total = ?, ping_samples = ?
                 WHERE id = ?
                """, at, duration, idleTime, pingTotal, pingSamples, sessionId);
    }

    /** Writes the final state of a session. */
    public void close(long sessionId, long endedAt, long duration, long gapTime, long idleTime,
                      String lastServer, int serversSeen, int chatMessages, int commands,
                      int kicks, String quitReason, long pingTotal, long pingSamples,
                      int viewDistance, String chatMode, int skinParts, String mainHand,
                      String mods) {
        writes.execute("""
                UPDATE js_sessions
                   SET open = 0, ended_at = ?, heartbeat_at = ?, duration = ?, gap_time = ?,
                       idle_time = ?, last_server = COALESCE(?, last_server), servers_seen = ?,
                       chat_messages = ?, commands = ?, kicks = ?, quit_reason = ?,
                       ping_total = ?, ping_samples = ?, view_distance = ?, chat_mode = ?,
                       skin_parts = ?, main_hand = ?, mods = ?
                 WHERE id = ?
                """, endedAt, endedAt, duration, gapTime, idleTime, lastServer, serversSeen,
                chatMessages, commands, kicks, quitReason, pingTotal, pingSamples, viewDistance,
                chatMode, skinParts, mainHand, mods, sessionId);
    }

    /**
     * Closes sessions left open by an unclean shutdown, at their last heartbeat.
     *
     * <p>Running this at startup is what keeps a crash from producing a session that appears to
     * still be running months later, and bounds the error to one heartbeat interval instead of
     * silently inventing playtime.
     *
     * @return how many sessions were recovered
     */
    public CompletableFuture<Integer> recoverOpenSessions() {
        return writes.submit(connection -> {
            int recovered;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE js_sessions
                       SET open     = 0,
                           crashed  = 1,
                           ended_at = CASE WHEN heartbeat_at > started_at
                                           THEN heartbeat_at ELSE started_at END,
                           duration = CASE WHEN heartbeat_at > started_at
                                           THEN heartbeat_at - started_at ELSE 0 END
                     WHERE open = 1
                    """)) {
                recovered = statement.executeUpdate();
            }
            if (recovered > 0) {
                // The profile totals never saw these sessions, so fold them in now.
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE js_players SET
                            playtime        = playtime + COALESCE((
                                SELECT SUM(duration) FROM js_sessions s
                                 WHERE s.uuid = js_players.uuid AND s.crashed = 1
                                   AND s.ended_at > js_players.last_quit), 0),
                            sessions        = sessions + COALESCE((
                                SELECT COUNT(*) FROM js_sessions s
                                 WHERE s.uuid = js_players.uuid AND s.crashed = 1
                                   AND s.ended_at > js_players.last_quit), 0),
                            longest_session = MAX(longest_session, COALESCE((
                                SELECT MAX(duration) FROM js_sessions s
                                 WHERE s.uuid = js_players.uuid AND s.crashed = 1
                                   AND s.ended_at > js_players.last_quit), 0)),
                            last_seen       = MAX(last_seen, COALESCE((
                                SELECT MAX(ended_at) FROM js_sessions s
                                 WHERE s.uuid = js_players.uuid AND s.crashed = 1
                                   AND s.ended_at > js_players.last_quit), 0)),
                            last_quit       = COALESCE((
                                SELECT MAX(ended_at) FROM js_sessions s
                                 WHERE s.uuid = js_players.uuid AND s.crashed = 1), last_quit)
                         WHERE uuid IN (SELECT uuid FROM js_sessions WHERE crashed = 1)
                        """)) {
                    statement.executeUpdate();
                }
                // Clear the marker so a later recovery cannot double-count these rows.
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE js_sessions SET crashed = 2 WHERE crashed = 1")) {
                    statement.executeUpdate();
                }
            }
            return recovered;
        });
    }

    // ------------------------------------------------------------------ server visits

    /** Opens a per-server visit inside a session and returns its id. */
    public CompletableFuture<Long> openVisit(long sessionId, UUID uuid, String server, long at) {
        return writes.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO js_session_servers (session_id, uuid, server, joined_at)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(statement, sessionId, uuid, server, at);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            }
        });
    }

    public void closeVisit(long visitId, long at, long duration) {
        writes.execute("UPDATE js_session_servers SET left_at = ?, duration = ? WHERE id = ?",
                at, duration, visitId);
    }

    /** Adds a completed visit to the per-account, per-server totals. */
    public void addServerPlaytime(UUID uuid, String server, long millis, int joins, long now) {
        writes.execute("""
                INSERT INTO js_player_servers (uuid, server, playtime, joins, first_seen, last_seen)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid, server) DO UPDATE SET
                    playtime  = js_player_servers.playtime + excluded.playtime,
                    joins     = js_player_servers.joins + excluded.joins,
                    last_seen = excluded.last_seen
                """, uuid, server, millis, joins, now, now);
    }

    // ------------------------------------------------------------------ activity

    /** Adds playtime to one cell of the hour-of-week heatmap. */
    public void addHourlyActivity(UUID uuid, int dayOfWeek, int hour, long millis, int sessions) {
        writes.execute("""
                INSERT INTO js_activity_hourly (uuid, day_of_week, hour, playtime, sessions)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid, day_of_week, hour) DO UPDATE SET
                    playtime = js_activity_hourly.playtime + excluded.playtime,
                    sessions = js_activity_hourly.sessions + excluded.sessions
                """, uuid, dayOfWeek, hour, millis, sessions);
    }

    /** Adds playtime to one calendar day. */
    public void addDailyActivity(UUID uuid, String day, long millis, int sessions,
                                 int connections) {
        writes.execute("""
                INSERT INTO js_activity_daily (uuid, day, playtime, sessions, connections)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid, day) DO UPDATE SET
                    playtime    = js_activity_daily.playtime + excluded.playtime,
                    sessions    = js_activity_daily.sessions + excluded.sessions,
                    connections = js_activity_daily.connections + excluded.connections
                """, uuid, day, millis, sessions, connections);
    }

    // ------------------------------------------------------------------ reads

    public CompletableFuture<List<SessionRecord>> recent(UUID uuid, int limit, int offset) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_sessions WHERE uuid = ? ORDER BY started_at DESC "
                            + "LIMIT ? OFFSET ?")) {
                Database.bind(statement, uuid, limit, offset);
                return Database.list(statement, SessionDao::mapSession);
            }
        });
    }

    public CompletableFuture<List<SessionRecord>> recentAcrossNetwork(int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_sessions ORDER BY started_at DESC LIMIT ?")) {
                Database.bind(statement, limit);
                return Database.list(statement, SessionDao::mapSession);
            }
        });
    }

    public CompletableFuture<List<Records.ServerPlaytime>> serversOf(UUID uuid) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT server, playtime, joins, first_seen, last_seen
                      FROM js_player_servers WHERE uuid = ? ORDER BY playtime DESC
                    """)) {
                Database.bind(statement, uuid);
                return Database.list(statement, rows -> new Records.ServerPlaytime(
                        rows.getString("server"), rows.getLong("playtime"), rows.getInt("joins"),
                        rows.getLong("first_seen"), rows.getLong("last_seen")));
            }
        });
    }

    public CompletableFuture<List<Records.ServerVisit>> visitsOf(long sessionId) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT session_id, server, joined_at, left_at, duration
                      FROM js_session_servers WHERE session_id = ? ORDER BY joined_at
                    """)) {
                Database.bind(statement, sessionId);
                return Database.list(statement, rows -> new Records.ServerVisit(
                        rows.getLong("session_id"), rows.getString("server"),
                        rows.getLong("joined_at"), rows.getLong("left_at"),
                        rows.getLong("duration")));
            }
        });
    }

    public CompletableFuture<List<Records.ActivityCell>> hourlyActivity(UUID uuid) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT day_of_week, hour, playtime, sessions FROM js_activity_hourly
                     WHERE uuid = ? ORDER BY day_of_week, hour
                    """)) {
                Database.bind(statement, uuid);
                return Database.list(statement, rows -> new Records.ActivityCell(
                        rows.getInt("day_of_week"), rows.getInt("hour"),
                        rows.getLong("playtime"), rows.getInt("sessions")));
            }
        });
    }

    public CompletableFuture<List<Records.DailyActivity>> dailyActivity(UUID uuid, int days) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT day, playtime, sessions, connections FROM js_activity_daily
                     WHERE uuid = ? ORDER BY day DESC LIMIT ?
                    """)) {
                Database.bind(statement, uuid, days);
                return Database.list(statement, rows -> new Records.DailyActivity(
                        rows.getString("day"), rows.getLong("playtime"), rows.getInt("sessions"),
                        rows.getInt("connections")));
            }
        });
    }

    /** Network-wide activity per hour of the week, for the {@code /joinstats activity} view. */
    public CompletableFuture<List<Records.ActivityCell>> networkActivity() {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT day_of_week, hour, SUM(playtime) AS playtime, SUM(sessions) AS sessions
                      FROM js_activity_hourly GROUP BY day_of_week, hour
                     ORDER BY day_of_week, hour
                    """)) {
                return Database.list(statement, rows -> new Records.ActivityCell(
                        rows.getInt("day_of_week"), rows.getInt("hour"),
                        rows.getLong("playtime"), rows.getInt("sessions")));
            }
        });
    }

    /** Aggregate playtime for a backend server across every account. */
    public CompletableFuture<List<Records.Tally>> serverTotals(int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT server AS k, SUM(playtime) AS c FROM js_player_servers
                     GROUP BY server ORDER BY c DESC LIMIT ?
                    """)) {
                Database.bind(statement, limit);
                return Database.list(statement, rows -> new Records.Tally(
                        rows.getString("k"), rows.getLong("c"),
                        dev.faboit.joinstats.velocity.util.Durations.format(rows.getLong("c"))));
            }
        });
    }

/**
     * The coordinates and time of the most recent session that had a resolvable location.
     *
     * <p>Feeds the impossible-travel check. Skips sessions whose address never geolocated, so a
     * player who once connected from a LAN address does not make the check unusable for them.
     */
    public CompletableFuture<java.util.Optional<LastLocation>> lastLocation(UUID uuid,
                                                                            long beforeSessionId) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT a.latitude AS lat, a.longitude AS lon, a.country AS country,
                           a.city AS city,
                           CASE WHEN s.ended_at > 0 THEN s.ended_at ELSE s.started_at END AS at
                      FROM js_sessions s
                      JOIN js_addresses a ON a.address = s.address
                     WHERE s.uuid = ? AND s.id < ?
                       AND a.latitude IS NOT NULL AND a.longitude IS NOT NULL
                     ORDER BY s.started_at DESC
                     LIMIT 1
                    """)) {
                Database.bind(statement, uuid, beforeSessionId);
                List<LastLocation> rows = Database.list(statement, result -> new LastLocation(
                        result.getDouble("lat"), result.getDouble("lon"),
                        result.getString("country"), result.getString("city"),
                        result.getLong("at")));
                return rows.isEmpty() ? java.util.Optional.<LastLocation>empty()
                        : java.util.Optional.of(rows.get(0));
            }
        });
    }

    /** Sessions that are still open and started before a cutoff — the long-session check. */
    public CompletableFuture<List<SessionRecord>> openSince(long cutoff) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_sessions WHERE open = 1 AND started_at <= ? "
                            + "ORDER BY started_at ASC")) {
                Database.bind(statement, cutoff);
                return Database.list(statement, SessionDao::mapSession);
            }
        });
    }

    /** Where and when an account was last seen from, for the impossible-travel check. */
    public record LastLocation(double latitude, double longitude, String country, String city,
                               long at) {
    }

    static SessionRecord mapSession(ResultSet rows) throws SQLException {
        return new SessionRecord(
                rows.getLong("id"),
                UUID.fromString(rows.getString("uuid")),
                rows.getString("username"),
                rows.getLong("started_at"),
                rows.getLong("ended_at"),
                rows.getLong("heartbeat_at"),
                rows.getLong("duration"),
                rows.getLong("gap_time"),
                rows.getLong("idle_time"),
                rows.getInt("connections"),
                rows.getInt("open") != 0,
                rows.getInt("crashed") != 0,
                rows.getString("address"),
                rows.getString("country_code"),
                rows.getString("city"),
                rows.getInt("protocol"),
                rows.getString("version_name"),
                rows.getString("brand"),
                rows.getString("locale"),
                rows.getString("virtual_host"),
                rows.getInt("online_mode") != 0,
                rows.getString("first_server"),
                rows.getString("last_server"),
                rows.getInt("servers_seen"),
                rows.getInt("chat_messages"),
                rows.getInt("commands"),
                rows.getInt("kicks"),
                rows.getString("quit_reason"),
                rows.getLong("ping_total"),
                rows.getLong("ping_samples"));
    }
}
