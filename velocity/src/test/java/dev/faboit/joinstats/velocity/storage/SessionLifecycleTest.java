package dev.faboit.joinstats.velocity.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.storage.dao.AddressDao;
import dev.faboit.joinstats.velocity.storage.dao.EventDao;
import dev.faboit.joinstats.velocity.storage.dao.PlayerDao;
import dev.faboit.joinstats.velocity.storage.dao.SessionDao;
import dev.faboit.joinstats.velocity.storage.model.PlayerProfile;
import dev.faboit.joinstats.velocity.storage.model.SessionRecord;
import dev.faboit.joinstats.velocity.tracking.SessionManager;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Exercises session tracking against a real SQLite file.
 *
 * <p>The rejoin grace window is the behaviour the whole plugin's statistics rest on: get it wrong
 * and session counts, average session length and join frequency all become measurements of the
 * players' internet connections instead of their habits. These tests drive the clock by hand so
 * the boundary can be checked exactly rather than waited out.
 */
class SessionLifecycleTest {

    private static final long SECOND = 1000L;
    private static final long GRACE_SECONDS = 30;

    @TempDir
    Path directory;

    private PluginConfig config;
    private Database database;
    private WriteQueue writes;
    private PlayerDao players;
    private SessionDao sessionDao;
    private SessionManager sessions;

    @BeforeEach
    void setUp() throws Exception {
        config = new PluginConfig();
        config.sessions.rejoinGrace = GRACE_SECONDS + "s";
        config.sessions.minimumMeaningfulSession = "10s";
        config.sessions.countGapAsPlaytime = false;
        config.storage.file = "test.db";
        config.storage.batchSize = 16;
        config.storage.flushInterval = "50";

        var logger = LoggerFactory.getLogger(SessionLifecycleTest.class);
        database = new Database(directory, config.storage, logger);
        writes = new WriteQueue(database, logger, config.storage.batchSize, 50L, 0);
        players = new PlayerDao(database, writes);
        sessionDao = new SessionDao(database, writes);
        AddressDao addresses = new AddressDao(database, writes);
        EventDao events = new EventDao(database, writes);
        sessions = new SessionManager(() -> config, players, sessionDao, addresses, events,
                () -> ZoneId.of("UTC"), logger);
    }

    @AfterEach
    void tearDown() {
        writes.close();
        database.close();
    }

    // ------------------------------------------------------------------ the grace window

    @Test
    void aRejoinInsideTheGraceWindowContinuesTheSameSession() {
        UUID player = UUID.randomUUID();
        long start = 1_700_000_000_000L;

        sessions.handleLogin(player, "Alice", start, connection());
        settle();
        long firstId = sessions.sessionId(player);
        assertTrue(firstId > 0, "the session row should exist as soon as the player is on");

        sessions.handleDisconnect(player, start + 10 * SECOND, "CANCELLED_BY_USER");
        // Well inside the window: the session must still be waiting, not written out.
        sessions.sweep(start + 12 * SECOND);
        assertTrue(sessions.session(player).isPresent(),
                "a session inside its grace window must not be finalised");

        sessions.handleLogin(player, "Alice", start + 15 * SECOND, connection());
        settle();
        assertEquals(firstId, sessions.sessionId(player),
                "reconnecting inside the grace window must continue the same session");

        sessions.handleDisconnect(player, start + 40 * SECOND, "CANCELLED_BY_USER");
        sessions.sweep(start + 40 * SECOND + (GRACE_SECONDS + 1) * SECOND);
        settle();

        List<SessionRecord> stored = sessionDao.recent(player, 10, 0).join();
        assertEquals(1, stored.size(), "one evening interrupted once is one session, not two");

        SessionRecord session = stored.get(0);
        assertEquals(2, session.connections());
        assertFalse(session.open());
        // 10s before the drop plus 25s after it. The 5s offline gap is excluded, because
        // count-gap-as-playtime is off.
        assertEquals(35 * SECOND, session.duration());
        assertEquals(5 * SECOND, session.gapTime());
        assertEquals(start, session.startedAt());
        assertEquals(start + 40 * SECOND, session.endedAt());
    }

    @Test
    void aRejoinAfterTheGraceWindowStartsAFreshSession() {
        UUID player = UUID.randomUUID();
        long start = 1_700_000_000_000L;

        sessions.handleLogin(player, "Bob", start, connection());
        settle();
        long firstId = sessions.sessionId(player);

        sessions.handleDisconnect(player, start + 60 * SECOND, "CANCELLED_BY_USER");
        // One second past the window is past the window.
        sessions.sweep(start + 60 * SECOND + (GRACE_SECONDS + 1) * SECOND);
        settle();

        sessions.handleLogin(player, "Bob", start + 200 * SECOND, connection());
        settle();
        long secondId = sessions.sessionId(player);

        assertNotEquals(firstId, secondId, "a rejoin past the window is a new session");
        sessions.handleDisconnect(player, start + 320 * SECOND, "CANCELLED_BY_USER");
        sessions.sweep(start + 400 * SECOND);
        settle();

        List<SessionRecord> stored = sessionDao.recent(player, 10, 0).join();
        assertEquals(2, stored.size());
        for (SessionRecord session : stored) {
            assertEquals(1, session.connections());
            assertEquals(0L, session.gapTime());
        }
    }

    @Test
    void theGraceWindowAbsorbsSeveralReconnectsInARow() {
        UUID player = UUID.randomUUID();
        long start = 1_700_000_000_000L;

        sessions.handleLogin(player, "Carol", start, connection());
        settle();
        long id = sessions.sessionId(player);

        // A bad ten minutes on a flaky connection: five drops, each recovered quickly.
        long cursor = start;
        for (int i = 0; i < 5; i++) {
            cursor += 60 * SECOND;
            sessions.handleDisconnect(player, cursor, "CANCELLED_BY_USER");
            sessions.sweep(cursor + 2 * SECOND);
            cursor += 5 * SECOND;
            sessions.handleLogin(player, "Carol", cursor, connection());
            settle();
            assertEquals(id, sessions.sessionId(player), "reconnect " + (i + 1) + " should merge");
        }

        sessions.handleDisconnect(player, cursor + 60 * SECOND, "CANCELLED_BY_USER");
        sessions.sweep(cursor + 60 * SECOND + (GRACE_SECONDS + 1) * SECOND);
        settle();

        List<SessionRecord> stored = sessionDao.recent(player, 10, 0).join();
        assertEquals(1, stored.size(), "five reconnects inside the window are still one session");
        assertEquals(6, stored.get(0).connections());
        assertEquals(25 * SECOND, stored.get(0).gapTime(), "five five-second gaps");
        assertEquals(360 * SECOND, stored.get(0).duration(), "six connected minutes");
    }

    @Test
    void countingTheGapAsPlaytimeIncludesTheOfflineStretch() {
        config.sessions.countGapAsPlaytime = true;
        UUID player = UUID.randomUUID();
        long start = 1_700_000_000_000L;

        sessions.handleLogin(player, "Dave", start, connection());
        settle();
        sessions.handleDisconnect(player, start + 10 * SECOND, "CANCELLED_BY_USER");
        sessions.handleLogin(player, "Dave", start + 20 * SECOND, connection());
        settle();
        sessions.handleDisconnect(player, start + 30 * SECOND, "CANCELLED_BY_USER");
        sessions.sweep(start + 30 * SECOND + (GRACE_SECONDS + 1) * SECOND);
        settle();

        SessionRecord session = sessionDao.recent(player, 1, 0).join().get(0);
        assertEquals(30 * SECOND, session.duration(), "end minus start, gap included");
        assertEquals(10 * SECOND, session.gapTime());
    }

    // ------------------------------------------------------------------ aggregates

    @Test
    void profileTotalsAccumulateAcrossSessions() {
        UUID player = UUID.randomUUID();
        long start = 1_700_000_000_000L;
        players.recordLogin(player, "Erin", start, "203.0.113.7", "GB", "United Kingdom",
                "London", 765, "1.20.1", "vanilla", "en_GB", true);

        playOneSession(player, "Erin", start, 300 * SECOND);
        playOneSession(player, "Erin", start + 3600 * SECOND, 600 * SECOND);
        settle();

        PlayerProfile profile = players.find(player).join().orElseThrow();
        assertEquals(2, profile.sessions());
        assertEquals(900 * SECOND, profile.playtime());
        assertEquals(600 * SECOND, profile.longestSession());
        assertEquals(450 * SECOND, profile.averageSession());
    }

    @Test
    void sessionsBelowTheMeaningfulThresholdDoNotSkewAverages() {
        UUID player = UUID.randomUUID();
        long start = 1_700_000_000_000L;
        players.recordLogin(player, "Frank", start, "203.0.113.8", null, null, null, 765,
                "1.20.1", null, null, true);

        playOneSession(player, "Frank", start, 600 * SECOND);
        // A failed handshake: connected, then gone in three seconds.
        playOneSession(player, "Frank", start + 3600 * SECOND, 3 * SECOND);
        settle();

        PlayerProfile profile = players.find(player).join().orElseThrow();
        assertEquals(1, profile.sessions(),
                "a three-second connection is recorded but must not count as a session");
        assertEquals(600 * SECOND, profile.playtime());

        // The row still exists — it is the aggregates that ignore it, not the history.
        assertEquals(2, sessionDao.recent(player, 10, 0).join().size());
    }

    // ------------------------------------------------------------------ crash recovery

    @Test
    void sessionsLeftOpenByACrashAreClosedAtTheirLastHeartbeat() {
        UUID player = UUID.randomUUID();
        long start = 1_700_000_000_000L;
        players.recordLogin(player, "Grace", start, "203.0.113.9", null, null, null, 765,
                "1.20.1", null, null, true);

        sessions.handleLogin(player, "Grace", start, connection());
        settle();
        // The proxy ran for two minutes and then died without a shutdown event.
        sessions.heartbeatAll(start + 120 * SECOND);
        settle();

        int recovered = sessionDao.recoverOpenSessions().join();
        settle();
        assertEquals(1, recovered);

        SessionRecord session = sessionDao.recent(player, 1, 0).join().get(0);
        assertFalse(session.open(), "a recovered session must not still look live");
        assertEquals(start + 120 * SECOND, session.endedAt(),
                "the error is bounded by the heartbeat interval, not invented");
        assertEquals(120 * SECOND, session.duration());

        PlayerProfile profile = players.find(player).join().orElseThrow();
        assertEquals(1, profile.sessions(), "recovery must fold the session into the profile");
        assertEquals(120 * SECOND, profile.playtime());

        // Running recovery again must not count the same session a second time.
        assertEquals(0, sessionDao.recoverOpenSessions().join());
        settle();
        assertEquals(120 * SECOND, players.find(player).join().orElseThrow().playtime());
    }

    @Test
    void shutdownWritesOutEverySession() {
        long start = 1_700_000_000_000L;
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        sessions.handleLogin(first, "Heidi", start, connection());
        sessions.handleLogin(second, "Ivan", start + SECOND, connection());
        settle();
        // Ivan dropped a moment ago and is still inside his grace window.
        sessions.handleDisconnect(second, start + 100 * SECOND, "CANCELLED_BY_USER");

        sessions.finaliseAll(start + 110 * SECOND, false);
        settle();

        assertEquals(0, sessions.all().size());
        assertEquals(110 * SECOND, sessionDao.recent(first, 1, 0).join().get(0).duration());
        assertEquals(99 * SECOND, sessionDao.recent(second, 1, 0).join().get(0).duration(),
                "a lingering session is written out at the time it actually ended");
    }

    // ------------------------------------------------------------------ helpers

    private void playOneSession(UUID player, String username, long start, long length) {
        sessions.handleLogin(player, username, start, connection());
        settle();
        sessions.handleDisconnect(player, start + length, "CANCELLED_BY_USER");
        sessions.sweep(start + length + (GRACE_SECONDS + 1) * SECOND);
        settle();
    }

    private static SessionManager.Connection connection() {
        return new SessionManager.Connection("203.0.113.7", "203.0.113.7", "GB", "London", 765,
                "1.20.1", "vanilla", "en_GB", "play.example.com", true);
    }

    /**
     * Waits for the writer to catch up.
     *
     * <p>Several writes are queued from callbacks that themselves run on the writer thread once
     * an earlier write completes — opening a session, then closing it once its id is known. One
     * flush only guarantees the first of those has landed, so drain repeatedly until the queue
     * stays empty.
     */
    private void settle() {
        for (int i = 0; i < 8; i++) {
            writes.flush().orTimeout(10, TimeUnit.SECONDS).join();
            if (writes.stats().queued() == 0) {
                // One more pass: a callback may have queued work as this one drained.
                writes.flush().orTimeout(10, TimeUnit.SECONDS).join();
                if (writes.stats().queued() == 0) {
                    return;
                }
            }
        }
    }
}
