package dev.faboit.joinstats.velocity.tracking;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The in-memory state of one session, live or lingering.
 *
 * <p>A session is a stretch of a player's <em>presence</em>, which is not the same thing as a TCP
 * connection. When someone's connection drops and they come back inside the configured grace
 * window, the same instance is resumed: {@link #connections} goes up, the offline stretch is
 * added to {@link #gapTime}, and the row in the database keeps its original id. Without that,
 * one flaky evening turns into forty "sessions" averaging ninety seconds, and every statistic
 * built on session count becomes a measure of the player's ISP.
 *
 * <p>Every event thread on the proxy can touch one of these, so the mutating methods are
 * synchronised. They are short and uncontended — one player's events are effectively serial.
 */
public final class LiveSession {

    final UUID uuid;
    final long startedAt;

    /** Completes with the row id once the opening INSERT lands. */
    final CompletableFuture<Long> id = new CompletableFuture<>();

    private final Set<String> serversSeen = new LinkedHashSet<>();

    private String username;
    private boolean online = true;

    /** Start of the current connected stretch. */
    private long connectedSince;

    /** Connected time from stretches that have already ended. */
    private long accumulated;

    private long gapTime;
    private long idleTime;
    private long lastActivityAt;
    private long disconnectedAt;
    private int connections = 1;

    private String address;
    private String subnet;
    private String countryCode;
    private String city;
    private int protocol;
    private String versionName;
    private String brand;
    private String locale;
    private String virtualHost;
    private boolean onlineMode;

    private String currentServer;
    private long currentServerSince;
    private long currentVisitId = -1;

    private int chatMessages;
    private int commands;
    private int kicks;
    private long pingTotal;
    private long pingSamples;

    private int viewDistance;
    private String chatMode;
    private int skinParts;
    private String mainHand;
    private String mods;
    private String quitReason;

    /** Set once the session has been written out, so a late event cannot resurrect it. */
    private boolean finalised;

    LiveSession(UUID uuid, String username, long startedAt) {
        this.uuid = uuid;
        this.username = username;
        this.startedAt = startedAt;
        this.connectedSince = startedAt;
        this.lastActivityAt = startedAt;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Ends the current connected stretch and begins lingering. */
    synchronized void markDisconnected(long now, String reason) {
        if (!online) {
            return;
        }
        accrueIdle(now, Long.MAX_VALUE);
        accumulated += Math.max(0L, now - connectedSince);
        online = false;
        disconnectedAt = now;
        quitReason = reason;
    }

    /** Resumes a lingering session after a reconnect inside the grace window. */
    synchronized void markResumed(long now, String username, String address, String subnet,
                                  int protocol, String virtualHost) {
        if (online) {
            return;
        }
        gapTime += Math.max(0L, now - disconnectedAt);
        online = true;
        disconnectedAt = 0L;
        connectedSince = now;
        lastActivityAt = now;
        connections++;
        quitReason = null;
        if (username != null) {
            this.username = username;
        }
        if (address != null) {
            this.address = address;
            this.subnet = subnet;
        }
        if (protocol > 0) {
            this.protocol = protocol;
        }
        if (virtualHost != null) {
            this.virtualHost = virtualHost;
        }
    }

    synchronized boolean markFinalised() {
        if (finalised) {
            return false;
        }
        finalised = true;
        return true;
    }

    // ------------------------------------------------------------------ derived time

    /** Connected time so far, excluding any offline gaps. */
    public synchronized long connectedMillis(long now) {
        return accumulated + (online ? Math.max(0L, now - connectedSince) : 0L);
    }

    /** The value to store as the session's duration, per the configured gap policy. */
    synchronized long duration(long endedAt, boolean countGapAsPlaytime) {
        return countGapAsPlaytime ? Math.max(0L, endedAt - startedAt) : connectedMillis(endedAt);
    }

    /**
     * Adds the portion of the time since the last activity that counts as idle.
     *
     * <p>Only the excess beyond the threshold counts, so a player who types every nine minutes
     * with a ten-minute threshold accrues no idle time at all, which is the intent.
     */
    synchronized void accrueIdle(long now, long thresholdMillis) {
        if (thresholdMillis <= 0 || !online) {
            lastActivityAt = now;
            return;
        }
        long quiet = now - lastActivityAt;
        if (quiet > thresholdMillis && thresholdMillis != Long.MAX_VALUE) {
            idleTime += quiet - thresholdMillis;
        }
        lastActivityAt = now;
    }

    /** Records activity without ending the idle stretch's accounting. */
    synchronized void touch(long now, long idleThresholdMillis) {
        accrueIdle(now, idleThresholdMillis);
    }

    // ------------------------------------------------------------------ mutation

    synchronized void applyIdentity(String address, String subnet, int protocol,
                                    String versionName, String virtualHost, boolean onlineMode) {
        this.address = address;
        this.subnet = subnet;
        this.protocol = protocol;
        this.versionName = versionName;
        this.virtualHost = virtualHost;
        this.onlineMode = onlineMode;
    }

    synchronized void applyLocation(String countryCode, String city) {
        this.countryCode = countryCode;
        this.city = city;
    }

    synchronized void applyBrand(String brand) {
        this.brand = brand;
    }

    synchronized void applySettings(String locale, int viewDistance, String chatMode,
                                    int skinParts, String mainHand) {
        this.locale = locale;
        this.viewDistance = viewDistance;
        this.chatMode = chatMode;
        this.skinParts = skinParts;
        this.mainHand = mainHand;
    }

    synchronized void applyMods(String mods) {
        this.mods = mods;
    }

    synchronized void recordPing(long millis) {
        pingTotal += millis;
        pingSamples++;
    }

    synchronized void countChat() {
        chatMessages++;
    }

    synchronized void countCommand() {
        commands++;
    }

    synchronized void countKick() {
        kicks++;
    }

    /**
     * Switches the tracked backend server.
     *
     * @return the visit that just ended, or {@code null} if this is the first server
     */
    synchronized ClosedVisit switchServer(String server, long now, long newVisitId) {
        ClosedVisit closed = closeCurrentVisit(now);
        currentServer = server;
        currentServerSince = now;
        currentVisitId = newVisitId;
        if (server != null) {
            serversSeen.add(server);
        }
        return closed;
    }

    /** Ends the open visit without starting another. */
    synchronized ClosedVisit closeCurrentVisit(long now) {
        if (currentServer == null || currentVisitId < 0) {
            return null;
        }
        ClosedVisit closed = new ClosedVisit(currentVisitId, currentServer,
                Math.max(0L, now - currentServerSince));
        currentVisitId = -1;
        return closed;
    }

    /** Records the id of a visit whose INSERT completed after the switch was processed. */
    synchronized void attachVisitId(String server, long visitId) {
        if (server.equals(currentServer) && currentVisitId < 0) {
            currentVisitId = visitId;
        }
    }

    // ------------------------------------------------------------------ accessors

    public synchronized String username() {
        return username;
    }

    public synchronized boolean online() {
        return online;
    }

    public synchronized boolean finalised() {
        return finalised;
    }

    public synchronized long disconnectedAt() {
        return disconnectedAt;
    }

    public synchronized long gapTime() {
        return gapTime;
    }

    public synchronized long idleTime() {
        return idleTime;
    }

    public synchronized int connections() {
        return connections;
    }

    public synchronized String address() {
        return address;
    }

    public synchronized String subnet() {
        return subnet;
    }

    public synchronized String countryCode() {
        return countryCode;
    }

    public synchronized String city() {
        return city;
    }

    public synchronized int protocol() {
        return protocol;
    }

    public synchronized String versionName() {
        return versionName;
    }

    public synchronized String brand() {
        return brand;
    }

    public synchronized String locale() {
        return locale;
    }

    public synchronized String virtualHost() {
        return virtualHost;
    }

    public synchronized boolean onlineMode() {
        return onlineMode;
    }

    public synchronized String currentServer() {
        return currentServer;
    }

    public synchronized int serversSeen() {
        return serversSeen.size();
    }

    public synchronized int chatMessages() {
        return chatMessages;
    }

    public synchronized int commands() {
        return commands;
    }

    public synchronized int kicks() {
        return kicks;
    }

    public synchronized long pingTotal() {
        return pingTotal;
    }

    public synchronized long pingSamples() {
        return pingSamples;
    }

    public synchronized int viewDistance() {
        return viewDistance;
    }

    public synchronized String chatMode() {
        return chatMode;
    }

    public synchronized int skinParts() {
        return skinParts;
    }

    public synchronized String mainHand() {
        return mainHand;
    }

    public synchronized String mods() {
        return mods;
    }

    public synchronized String quitReason() {
        return quitReason;
    }

    /** A per-server visit that has just ended. */
    record ClosedVisit(long visitId, String server, long duration) {
    }
}
