package dev.faboit.joinstats.velocity.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.slf4j.Logger;

/**
 * Creates and upgrades the tables.
 *
 * <p>The version is stored in {@code js_meta}. Each entry in {@link #MIGRATIONS} takes the
 * database from version {@code n} to {@code n + 1}; adding a column in a future release means
 * appending an array here, never editing an existing one.
 */
final class Schema {

    /** Table prefix, kept explicit so the file can be shared with an unrelated schema. */
    static final String P = "js_";

    private static final String[] INITIAL = {
            // ---- identity ---------------------------------------------------
            """
            CREATE TABLE IF NOT EXISTS js_meta (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS js_players (
                uuid            TEXT PRIMARY KEY,
                username        TEXT NOT NULL,
                username_key    TEXT NOT NULL,
                first_seen      INTEGER NOT NULL,
                last_seen       INTEGER NOT NULL,
                last_quit       INTEGER NOT NULL DEFAULT 0,
                playtime        INTEGER NOT NULL DEFAULT 0,
                idle_time       INTEGER NOT NULL DEFAULT 0,
                sessions        INTEGER NOT NULL DEFAULT 0,
                connections     INTEGER NOT NULL DEFAULT 0,
                longest_session INTEGER NOT NULL DEFAULT 0,
                kicks           INTEGER NOT NULL DEFAULT 0,
                chat_messages   INTEGER NOT NULL DEFAULT 0,
                commands        INTEGER NOT NULL DEFAULT 0,
                server_switches INTEGER NOT NULL DEFAULT 0,
                last_address    TEXT,
                last_country    TEXT,
                last_country_code TEXT,
                last_city       TEXT,
                last_server     TEXT,
                first_protocol  INTEGER NOT NULL DEFAULT 0,
                last_protocol   INTEGER NOT NULL DEFAULT 0,
                last_version    TEXT,
                last_brand      TEXT,
                last_locale     TEXT,
                online_mode     INTEGER NOT NULL DEFAULT 1,
                ping_total      INTEGER NOT NULL DEFAULT 0,
                ping_samples    INTEGER NOT NULL DEFAULT 0,
                ping_best       INTEGER NOT NULL DEFAULT 0,
                ping_worst      INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_players_key ON js_players(username_key)",
            "CREATE INDEX IF NOT EXISTS js_players_last_seen ON js_players(last_seen DESC)",
            "CREATE INDEX IF NOT EXISTS js_players_playtime ON js_players(playtime DESC)",
            "CREATE INDEX IF NOT EXISTS js_players_first_seen ON js_players(first_seen DESC)",
            """
            CREATE TABLE IF NOT EXISTS js_usernames (
                uuid         TEXT NOT NULL,
                username     TEXT NOT NULL,
                username_key TEXT NOT NULL,
                first_seen   INTEGER NOT NULL,
                last_seen    INTEGER NOT NULL,
                connections  INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, username_key)
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_usernames_key ON js_usernames(username_key)",

            // ---- addresses --------------------------------------------------
            """
            CREATE TABLE IF NOT EXISTS js_addresses (
                address       TEXT PRIMARY KEY,
                subnet        TEXT,
                first_seen    INTEGER NOT NULL,
                last_seen     INTEGER NOT NULL,
                hits          INTEGER NOT NULL DEFAULT 0,
                hostname      TEXT,
                continent     TEXT,
                country       TEXT,
                country_code  TEXT,
                region        TEXT,
                city          TEXT,
                postal        TEXT,
                latitude      REAL,
                longitude     REAL,
                accuracy_km   INTEGER,
                timezone      TEXT,
                isp           TEXT,
                organisation  TEXT,
                asn           INTEGER,
                as_name       TEXT,
                is_mobile     INTEGER NOT NULL DEFAULT 0,
                is_proxy      INTEGER NOT NULL DEFAULT 0,
                is_hosting    INTEGER NOT NULL DEFAULT 0,
                is_tor        INTEGER NOT NULL DEFAULT 0,
                geo_source    TEXT,
                geo_updated   INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_addresses_subnet ON js_addresses(subnet)",
            "CREATE INDEX IF NOT EXISTS js_addresses_country ON js_addresses(country_code)",
            "CREATE INDEX IF NOT EXISTS js_addresses_asn ON js_addresses(asn)",
            """
            CREATE TABLE IF NOT EXISTS js_player_addresses (
                uuid        TEXT NOT NULL,
                address     TEXT NOT NULL,
                subnet      TEXT,
                first_seen  INTEGER NOT NULL,
                last_seen   INTEGER NOT NULL,
                connections INTEGER NOT NULL DEFAULT 0,
                playtime    INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, address)
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_player_addresses_addr ON js_player_addresses(address, last_seen DESC)",
            "CREATE INDEX IF NOT EXISTS js_player_addresses_subnet ON js_player_addresses(subnet, last_seen DESC)",

            // ---- sessions ---------------------------------------------------
            """
            CREATE TABLE IF NOT EXISTS js_sessions (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid           TEXT NOT NULL,
                username       TEXT NOT NULL,
                started_at     INTEGER NOT NULL,
                ended_at       INTEGER NOT NULL DEFAULT 0,
                heartbeat_at   INTEGER NOT NULL DEFAULT 0,
                duration       INTEGER NOT NULL DEFAULT 0,
                gap_time       INTEGER NOT NULL DEFAULT 0,
                idle_time      INTEGER NOT NULL DEFAULT 0,
                connections    INTEGER NOT NULL DEFAULT 1,
                open           INTEGER NOT NULL DEFAULT 1,
                crashed        INTEGER NOT NULL DEFAULT 0,
                address        TEXT,
                subnet         TEXT,
                country_code   TEXT,
                city           TEXT,
                protocol       INTEGER NOT NULL DEFAULT 0,
                version_name   TEXT,
                brand          TEXT,
                locale         TEXT,
                virtual_host   TEXT,
                online_mode    INTEGER NOT NULL DEFAULT 1,
                first_server   TEXT,
                last_server    TEXT,
                servers_seen   INTEGER NOT NULL DEFAULT 0,
                chat_messages  INTEGER NOT NULL DEFAULT 0,
                commands       INTEGER NOT NULL DEFAULT 0,
                kicks          INTEGER NOT NULL DEFAULT 0,
                quit_reason    TEXT,
                ping_total     INTEGER NOT NULL DEFAULT 0,
                ping_samples   INTEGER NOT NULL DEFAULT 0,
                view_distance  INTEGER NOT NULL DEFAULT 0,
                chat_mode      TEXT,
                skin_parts     INTEGER NOT NULL DEFAULT 0,
                main_hand      TEXT,
                mods           TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_sessions_player ON js_sessions(uuid, started_at DESC)",
            "CREATE INDEX IF NOT EXISTS js_sessions_started ON js_sessions(started_at DESC)",
            "CREATE INDEX IF NOT EXISTS js_sessions_open ON js_sessions(open)",
            """
            CREATE TABLE IF NOT EXISTS js_session_servers (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                uuid       TEXT NOT NULL,
                server     TEXT NOT NULL,
                joined_at  INTEGER NOT NULL,
                left_at    INTEGER NOT NULL DEFAULT 0,
                duration   INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_session_servers_session ON js_session_servers(session_id)",
            "CREATE INDEX IF NOT EXISTS js_session_servers_player ON js_session_servers(uuid, joined_at DESC)",
            """
            CREATE TABLE IF NOT EXISTS js_player_servers (
                uuid       TEXT NOT NULL,
                server     TEXT NOT NULL,
                playtime   INTEGER NOT NULL DEFAULT 0,
                joins      INTEGER NOT NULL DEFAULT 0,
                first_seen INTEGER NOT NULL,
                last_seen  INTEGER NOT NULL,
                PRIMARY KEY (uuid, server)
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_player_servers_server ON js_player_servers(server, playtime DESC)",

            // ---- activity ---------------------------------------------------
            """
            CREATE TABLE IF NOT EXISTS js_activity_hourly (
                uuid        TEXT NOT NULL,
                day_of_week INTEGER NOT NULL,
                hour        INTEGER NOT NULL,
                playtime    INTEGER NOT NULL DEFAULT 0,
                sessions    INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, day_of_week, hour)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS js_activity_daily (
                uuid        TEXT NOT NULL,
                day         TEXT NOT NULL,
                playtime    INTEGER NOT NULL DEFAULT 0,
                sessions    INTEGER NOT NULL DEFAULT 0,
                connections INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, day)
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_activity_daily_day ON js_activity_daily(day)",

            // ---- event log --------------------------------------------------
            """
            CREATE TABLE IF NOT EXISTS js_events (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                at          INTEGER NOT NULL,
                type        TEXT NOT NULL,
                uuid        TEXT,
                username    TEXT,
                address     TEXT,
                server      TEXT,
                from_server TEXT,
                session_id  INTEGER,
                detail      TEXT,
                data        TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_events_at ON js_events(at DESC)",
            "CREATE INDEX IF NOT EXISTS js_events_player ON js_events(uuid, at DESC)",
            "CREATE INDEX IF NOT EXISTS js_events_type ON js_events(type, at DESC)",
            """
            CREATE TABLE IF NOT EXISTS js_chat (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                at        INTEGER NOT NULL,
                uuid      TEXT NOT NULL,
                username  TEXT NOT NULL,
                server    TEXT,
                message   TEXT,
                length    INTEGER NOT NULL DEFAULT 0,
                cancelled INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_chat_player ON js_chat(uuid, at DESC)",
            "CREATE INDEX IF NOT EXISTS js_chat_at ON js_chat(at DESC)",
            """
            CREATE TABLE IF NOT EXISTS js_commands (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                at        INTEGER NOT NULL,
                uuid      TEXT,
                username  TEXT,
                server    TEXT,
                command   TEXT NOT NULL,
                arguments TEXT,
                cancelled INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_commands_player ON js_commands(uuid, at DESC)",
            "CREATE INDEX IF NOT EXISTS js_commands_name ON js_commands(command, at DESC)",
            """
            CREATE TABLE IF NOT EXISTS js_pings (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                at           INTEGER NOT NULL,
                address      TEXT NOT NULL,
                virtual_host TEXT,
                protocol     INTEGER NOT NULL DEFAULT 0,
                version_name TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_pings_at ON js_pings(at DESC)",
            "CREATE INDEX IF NOT EXISTS js_pings_address ON js_pings(address, at DESC)",

            // ---- population -------------------------------------------------
            """
            CREATE TABLE IF NOT EXISTS js_population (
                at    INTEGER PRIMARY KEY,
                total INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS js_population_breakdown (
                at    INTEGER NOT NULL,
                scope TEXT NOT NULL,
                key   TEXT NOT NULL,
                count INTEGER NOT NULL,
                PRIMARY KEY (at, scope, key)
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_population_breakdown_scope ON js_population_breakdown(scope, key, at DESC)",
            """
            CREATE TABLE IF NOT EXISTS js_population_rollup (
                width    INTEGER NOT NULL,
                bucket   INTEGER NOT NULL,
                scope    TEXT NOT NULL,
                key      TEXT NOT NULL,
                samples  INTEGER NOT NULL,
                total    INTEGER NOT NULL,
                minimum  INTEGER NOT NULL,
                maximum  INTEGER NOT NULL,
                PRIMARY KEY (width, scope, key, bucket)
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_population_rollup_bucket ON js_population_rollup(width, bucket DESC)",

            // ---- placeholders -----------------------------------------------
            """
            CREATE TABLE IF NOT EXISTS js_placeholders (
                uuid        TEXT NOT NULL,
                placeholder TEXT NOT NULL,
                value       TEXT,
                server      TEXT,
                updated_at  INTEGER NOT NULL,
                PRIMARY KEY (uuid, placeholder)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS js_placeholder_history (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                at          INTEGER NOT NULL,
                uuid        TEXT NOT NULL,
                placeholder TEXT NOT NULL,
                value       TEXT,
                server      TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_placeholder_history_player ON js_placeholder_history(uuid, placeholder, at DESC)",
            "CREATE INDEX IF NOT EXISTS js_placeholder_history_at ON js_placeholder_history(at DESC)",

            // ---- annotations ------------------------------------------------
            """
            CREATE TABLE IF NOT EXISTS js_alerts (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                at           INTEGER NOT NULL,
                type         TEXT NOT NULL,
                severity     TEXT NOT NULL,
                uuid         TEXT,
                username     TEXT,
                message      TEXT NOT NULL,
                data         TEXT,
                acknowledged INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_alerts_at ON js_alerts(at DESC)",
            "CREATE INDEX IF NOT EXISTS js_alerts_player ON js_alerts(uuid, at DESC)",
            "CREATE INDEX IF NOT EXISTS js_alerts_type ON js_alerts(type, at DESC)",
            """
            CREATE TABLE IF NOT EXISTS js_notes (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                at          INTEGER NOT NULL,
                uuid        TEXT NOT NULL,
                author      TEXT NOT NULL,
                author_uuid TEXT,
                note        TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_notes_player ON js_notes(uuid, at DESC)",
            """
            CREATE TABLE IF NOT EXISTS js_tags (
                uuid     TEXT NOT NULL,
                tag      TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                added_by TEXT,
                PRIMARY KEY (uuid, tag)
            )
            """,
            "CREATE INDEX IF NOT EXISTS js_tags_tag ON js_tags(tag)",
            """
            CREATE TABLE IF NOT EXISTS js_counters (
                key     TEXT PRIMARY KEY,
                value   INTEGER NOT NULL DEFAULT 0,
                at      INTEGER NOT NULL DEFAULT 0,
                detail  TEXT
            )
            """,
    };

    /**
     * Upgrades, applied in order. Index {@code i} moves the database from version
     * {@code i + 1} to {@code i + 2}.
     */
    private static final List<String[]> MIGRATIONS = List.of();

    private Schema() {
    }

    static void apply(Connection connection, Logger logger) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String ddl : INITIAL) {
                statement.executeUpdate(ddl);
            }
            connection.commit();

            int current = readVersion(connection);
            int target = 1 + MIGRATIONS.size();
            if (current == 0) {
                writeVersion(connection, target);
                connection.commit();
                logger.info("Initialised the statistics database at schema version {}.", target);
                return;
            }
            if (current > target) {
                throw new SQLException("The database is at schema version " + current
                        + " but this build only understands " + target
                        + ". Downgrading would lose data; install the newer plugin version.");
            }
            for (int version = current; version < target; version++) {
                for (String ddl : MIGRATIONS.get(version - 1)) {
                    statement.executeUpdate(ddl);
                }
                writeVersion(connection, version + 1);
                connection.commit();
                logger.info("Migrated the statistics database to schema version {}.", version + 1);
            }
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static int readVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT value FROM js_meta WHERE key = 'schema_version'")) {
            if (!rows.next()) {
                return 0;
            }
            try {
                return Integer.parseInt(rows.getString(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private static void writeVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO js_meta(key, value) VALUES ('schema_version', '" + version + "') "
                            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value");
        }
    }
}
