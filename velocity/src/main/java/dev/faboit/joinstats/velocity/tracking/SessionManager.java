package dev.faboit.joinstats.velocity.tracking;

import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.storage.dao.AddressDao;
import dev.faboit.joinstats.velocity.storage.dao.EventDao;
import dev.faboit.joinstats.velocity.storage.dao.PlayerDao;
import dev.faboit.joinstats.velocity.storage.dao.SessionDao;
import dev.faboit.joinstats.velocity.util.Ticks;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Owns the lifecycle of every session: opening, resuming, heartbeating and finalising.
 *
 * <p>The interesting part is what happens between a disconnect and the grace window expiring.
 * The session is not closed — it lingers in {@link #sessions}, still holding its database id.
 * If the player comes back, {@link #handleLogin} finds it and resumes; if the sweeper reaches
 * it first, it is written out. That single rule is what makes session counts, average session
 * length and the join-frequency statistics mean anything on a network where a proxy restart or
 * a bad wifi minute would otherwise fragment one evening into a dozen "sessions".
 */
public final class SessionManager {

    private final Supplier<PluginConfig> config;
    private final PlayerDao players;
    private final SessionDao sessions;
    private final AddressDao addresses;
    private final EventDao events;
    private final Logger logger;
    private final Supplier<ZoneId> zone;

    private final Map<UUID, LiveSession> live = new ConcurrentHashMap<>();
    private volatile SessionEvents listener = SessionEvents.NONE;

    public SessionManager(Supplier<PluginConfig> config, PlayerDao players, SessionDao sessions,
                          AddressDao addresses, EventDao events, Supplier<ZoneId> zone,
                          Logger logger) {
        this.config = config;
        this.players = players;
        this.sessions = sessions;
        this.addresses = addresses;
        this.events = events;
        this.zone = zone;
        this.logger = logger;
    }

    public void listener(SessionEvents listener) {
        this.listener = listener == null ? SessionEvents.NONE : listener;
    }

    // ------------------------------------------------------------------ queries

    /** The live or lingering session for an account, if there is one. */
    public Optional<LiveSession> session(UUID uuid) {
        return Optional.ofNullable(live.get(uuid));
    }

    /** How many sessions are currently open, not counting lingering ones. */
    public int onlineCount() {
        int count = 0;
        for (LiveSession session : live.values()) {
            if (session.online()) {
                count++;
            }
        }
        return count;
    }

    /** Every tracked session, live and lingering. */
    public Collection<LiveSession> all() {
        return live.values();
    }

    /** The current session id for an account, or {@code -1} if not yet assigned. */
    public long sessionId(UUID uuid) {
        LiveSession session = live.get(uuid);
        if (session == null) {
            return -1L;
        }
        return session.id.getNow(-1L);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts or resumes a session for a player who has just finished logging in. */
    public void handleLogin(UUID uuid, String username, long now, Connection connection) {
        PluginConfig settings = config.get();
        long graceMillis = settings.sessions.grace().toMillis();

        LiveSession existing = live.get(uuid);
        if (existing != null && !existing.finalised()) {
            if (existing.online()) {
                // The proxy accepted a second connection for the same account before the first
                // disconnect was delivered. Close the stale stretch so its time is not
                // double-counted against the new one.
                logger.debug("{} logged in while a session was still marked online; "
                        + "closing the previous stretch.", username);
                existing.markDisconnected(now, "replaced");
            }
            if (now - existing.disconnectedAt() <= graceMillis) {
                resume(existing, uuid, username, now, connection);
                return;
            }
            finalise(existing, existing.disconnectedAt(), false);
        }

        LiveSession session = new LiveSession(uuid, username, now);
        session.applyIdentity(connection.address(), connection.subnet(), connection.protocol(),
                connection.versionName(), connection.virtualHost(), connection.onlineMode());
        live.put(uuid, session);

        sessions.open(uuid, username, now, connection.address(), connection.subnet(),
                        connection.countryCode(), connection.city(), connection.protocol(),
                        connection.versionName(), connection.brand(), connection.locale(),
                        connection.virtualHost(), connection.onlineMode(), null)
                .whenComplete((id, error) -> {
                    if (error != null || id == null || id < 0) {
                        session.id.complete(-1L);
                        logger.error("Could not open a session row for {}; this session will not "
                                + "be recorded.", username, error);
                        return;
                    }
                    session.id.complete(id);
                    listener.onSessionStarted(uuid, username, id, now);
                });

        events.record(now, "login", uuid, username, connection.address(), null, null, null,
                connection.versionName(), null);
    }

    private void resume(LiveSession session, UUID uuid, String username, long now,
                        Connection connection) {
        long gap = Math.max(0L, now - session.disconnectedAt());
        session.markResumed(now, username, connection.address(), connection.subnet(),
                connection.protocol(), connection.virtualHost());
        int connections = session.connections();

        session.id.thenAccept(id -> {
            if (id < 0) {
                return;
            }
            sessions.resume(id, now, connection.address(), connection.subnet(),
                    connection.protocol(), connection.virtualHost());
            listener.onSessionResumed(uuid, username, id, gap, connections);
        });

        events.record(now, "rejoin", uuid, username, connection.address(), null, null,
                session.id.getNow(-1L), "gap=" + gap + "ms", null);
        logger.debug("Resumed {}'s session after a {}ms gap (connection {}).",
                username, gap, connections);
    }

    /** Marks a session as lingering. It is written out later, by the sweeper. */
    public void handleDisconnect(UUID uuid, long now, String reason) {
        LiveSession session = live.get(uuid);
        if (session == null || session.finalised()) {
            return;
        }
        session.markDisconnected(now, reason);
        events.record(now, "disconnect", uuid, session.username(), session.address(),
                session.currentServer(), null, session.id.getNow(-1L), reason, null);

        // With no grace window configured there is nothing to wait for.
        if (config.get().sessions.grace().isZero()) {
            finalise(session, now, false);
        }
    }

    /** Records a move to a backend server, closing the previous visit. */
    public void handleServerConnected(UUID uuid, String server, String previousServer, long now) {
        LiveSession session = live.get(uuid);
        if (session == null || session.finalised()) {
            return;
        }
        session.touch(now, config.get().sessions.idle().toMillis());

        LiveSession.ClosedVisit closed = session.switchServer(server, now, -1L);
        applyClosedVisit(uuid, closed, now);

        session.id.thenAccept(id -> {
            if (id < 0) {
                return;
            }
            sessions.openVisit(id, uuid, server, now).thenAccept(visitId -> {
                if (visitId != null && visitId >= 0) {
                    session.attachVisitId(server, visitId);
                }
            });
        });

        if (previousServer != null) {
            players.increment(uuid, PlayerDao.Counter.SERVER_SWITCHES, 1);
        }
        events.record(now, previousServer == null ? "server-join" : "server-switch", uuid,
                session.username(), session.address(), server, previousServer,
                session.id.getNow(-1L), null, null);
    }

    private void applyClosedVisit(UUID uuid, LiveSession.ClosedVisit closed, long now) {
        if (closed == null || closed.duration() <= 0) {
            return;
        }
        if (closed.visitId() >= 0) {
            sessions.closeVisit(closed.visitId(), now, closed.duration());
        }
        sessions.addServerPlaytime(uuid, closed.server(), closed.duration(), 1, now);
    }

    // ------------------------------------------------------------------ periodic

    /**
     * Expires lingering sessions and splits over-long ones.
     *
     * <p>Called on a fixed schedule, as is {@link #heartbeatAll} on its own (longer) one.
     * Everything time-dependent about a session happens in these two passes rather than on a
     * timer per session, so the cost stays proportional to the number of online players and not
     * to the number of scheduled tasks.
     */
    public void sweep(long now) {
        PluginConfig settings = config.get();
        long graceMillis = settings.sessions.grace().toMillis();
        long maximum = settings.sessions.maximum().toMillis();

        for (LiveSession session : new ArrayList<>(live.values())) {
            if (session.finalised()) {
                live.remove(session.uuid, session);
                continue;
            }

            if (!session.online()) {
                if (now - session.disconnectedAt() > graceMillis) {
                    finalise(session, session.disconnectedAt(), false);
                }
                continue;
            }

            if (maximum > 0 && now - session.startedAt > maximum) {
                logger.debug("Splitting {}'s session after reaching the {}ms maximum.",
                        session.username(), maximum);
                splitSession(session, now);
            }
        }
    }

    /** Writes the current state of every open session, so a crash loses at most one interval. */
    public void heartbeatAll(long now) {
        for (LiveSession session : live.values()) {
            if (session.online() && !session.finalised()) {
                writeHeartbeat(session, now);
            }
        }
    }

    private void writeHeartbeat(LiveSession session, long now) {
        long id = session.id.getNow(-1L);
        if (id < 0) {
            return;
        }
        boolean countGap = config.get().sessions.countGapAsPlaytime;
        sessions.heartbeat(id, now, session.duration(now, countGap), session.idleTime(),
                session.pingTotal(), session.pingSamples());
    }

    /**
     * Closes an over-long session and opens a fresh one for the still-connected player.
     *
     * <p>Someone who leaves a client running for a week should not produce a single session row
     * that swallows their whole history and defeats every per-session statistic.
     */
    private void splitSession(LiveSession session, long now) {
        UUID uuid = session.uuid;
        String username = session.username();
        Connection carried = new Connection(session.address(), session.subnet(),
                session.countryCode(), session.city(), session.protocol(), session.versionName(),
                session.brand(), session.locale(), session.virtualHost(), session.onlineMode());
        String server = session.currentServer();

        session.markDisconnected(now, "session-split");
        finalise(session, now, false);
        live.remove(uuid, session);

        handleLogin(uuid, username, now, carried);
        if (server != null) {
            handleServerConnected(uuid, server, null, now);
        }
    }

    // ------------------------------------------------------------------ finalisation

    /** Writes a session out and folds it into the account's aggregates. */
    public void finalise(LiveSession session, long endedAt, boolean crashed) {
        if (!session.markFinalised()) {
            return;
        }
        live.remove(session.uuid, session);

        PluginConfig settings = config.get();
        UUID uuid = session.uuid;
        String username = session.username();
        long duration = session.duration(endedAt, settings.sessions.countGapAsPlaytime);
        long idleTime = session.idleTime();

        LiveSession.ClosedVisit closed = session.closeCurrentVisit(endedAt);
        applyClosedVisit(uuid, closed, endedAt);

        session.id.thenAccept(id -> {
            if (id < 0) {
                return;
            }
            sessions.close(id, endedAt, duration, session.gapTime(), idleTime,
                    session.currentServer(), session.serversSeen(), session.chatMessages(),
                    session.commands(), session.kicks(), session.quitReason(),
                    session.pingTotal(), session.pingSamples(), session.viewDistance(),
                    session.chatMode(), session.skinParts(), session.mainHand(), session.mods());

            long minimum = settings.sessions.minimumMeaningful().toMillis();
            if (duration >= minimum) {
                players.applySessionEnd(uuid, endedAt, duration, idleTime,
                        session.currentServer());
                if (session.address() != null) {
                    addresses.addPlaytime(uuid, session.address(), duration);
                }
                recordActivity(uuid, session.startedAt, endedAt, duration, session.connections());
            } else {
                // Too short to be meaningful. The account was still seen, but counting it would
                // drag every session-shaped statistic toward whatever the failed handshakes cost.
                players.touchSeen(uuid, endedAt, session.currentServer());
            }

            events.record(endedAt, "session-end", uuid, username, session.address(),
                    session.currentServer(), null, id,
                    duration + "ms over " + session.connections() + " connection(s)", null);
            listener.onSessionEnded(uuid, username, id, duration, endedAt, crashed);
        });
    }

    /**
     * Spreads a session's playtime across the hour-of-week and calendar-day buckets it covers.
     *
     * <p>Each hour boundary the session crosses gets its own share. Attributing the whole session
     * to the hour it started in would make every heatmap peak at whatever time people log on and
     * show nothing about when they actually play.
     */
    private void recordActivity(UUID uuid, long startedAt, long endedAt, long duration,
                                int connections) {
        long wall = Math.max(1L, endedAt - startedAt);
        // When gaps are excluded, scale each slice so the parts still sum to the stored duration.
        double scale = Math.min(1.0, (double) duration / (double) wall);
        boolean[] firstSlice = {true};

        Ticks.forEachHourSlice(startedAt, endedAt, zone.get(), (dayOfWeek, hour, day, millis) -> {
            long attributed = Math.round(millis * scale);
            if (attributed <= 0 && !firstSlice[0]) {
                return;
            }
            int sessionCount = firstSlice[0] ? 1 : 0;
            int connectionCount = firstSlice[0] ? connections : 0;
            firstSlice[0] = false;
            sessions.addHourlyActivity(uuid, dayOfWeek, hour, attributed, sessionCount);
            sessions.addDailyActivity(uuid, day, attributed, sessionCount, connectionCount);
        });
    }

    /** Writes out every session, live and lingering. Called during shutdown. */
    public void finaliseAll(long now, boolean crashed) {
        List<LiveSession> snapshot = new ArrayList<>(live.values());
        for (LiveSession session : snapshot) {
            long endedAt = session.online() ? now : session.disconnectedAt();
            if (session.online()) {
                session.markDisconnected(now, "proxy-shutdown");
            }
            finalise(session, endedAt, crashed);
        }
        live.clear();
    }

    /** The connection facts a session needs, gathered once at login. */
    public record Connection(String address, String subnet, String countryCode, String city,
                             int protocol, String versionName, String brand, String locale,
                             String virtualHost, boolean onlineMode) {
    }
}
