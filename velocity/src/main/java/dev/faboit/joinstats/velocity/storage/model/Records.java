package dev.faboit.joinstats.velocity.storage.model;

import java.util.List;
import java.util.UUID;

/** The smaller row shapes, grouped rather than scattered across a dozen one-record files. */
public final class Records {

    private Records() {
    }

    /** A row of the generic event log. */
    public record Event(long id, long at, String type, UUID uuid, String username, String address,
                        String server, String fromServer, Long sessionId, String detail,
                        String data) {
    }

    /** A recorded chat message. */
    public record ChatLine(long id, long at, UUID uuid, String username, String server,
                           String message, int length, boolean cancelled) {
    }

    /** A recorded command execution. */
    public record CommandLine(long id, long at, UUID uuid, String username, String server,
                              String command, String arguments, boolean cancelled) {
    }

    /** A server-list ping. */
    public record PingEntry(long id, long at, String address, String virtualHost, int protocol,
                            String versionName) {
    }

    /** Time one account spent on one backend server. */
    public record ServerPlaytime(String server, long playtime, int joins, long firstSeen,
                                 long lastSeen) {
    }

    /** One visit to a backend inside a session. */
    public record ServerVisit(long sessionId, String server, long joinedAt, long leftAt,
                              long duration) {
    }

    /** An address linked to an account. */
    public record PlayerAddress(String address, String subnet, long firstSeen, long lastSeen,
                                int connections, long playtime) {
    }

    /** Another account seen on a shared address. */
    public record AltAccount(UUID uuid, String username, String address, long lastShared,
                             int sharedAddresses, boolean exactMatch) {
    }

    /** A username this account has connected under. */
    public record NameHistoryEntry(String username, long firstSeen, long lastSeen, int connections) {
    }

    /** The latest known value of one placeholder. */
    public record PlaceholderValue(String placeholder, String value, String server,
                                   long updatedAt) {
    }

    /** A historical placeholder observation. */
    public record PlaceholderPoint(long at, String value, String server) {
    }

    /** A raw population sample. */
    public record PopulationSample(long at, int total) {
    }

    /** An aggregated population bucket. */
    public record PopulationBucket(long bucket, long width, String scope, String key, int samples,
                                   long total, int minimum, int maximum) {
        public double average() {
            return samples <= 0 ? 0.0 : (double) total / (double) samples;
        }
    }

    /** One cell of the hour-of-week activity heatmap. */
    public record ActivityCell(int dayOfWeek, int hour, long playtime, int sessions) {
    }

    /** One day of activity. */
    public record DailyActivity(String day, long playtime, int sessions, int connections) {
    }

    /** A flagged pattern. */
    public record Alert(long id, long at, String type, String severity, UUID uuid, String username,
                        String message, String data, boolean acknowledged) {
    }

    /** A staff note attached to an account. */
    public record Note(long id, long at, UUID uuid, String author, UUID authorUuid, String note) {
    }

    /** A label attached to an account. */
    public record Tag(String tag, long addedAt, String addedBy) {
    }

    /** A leaderboard row. */
    public record Ranked(int rank, UUID uuid, String username, double value, String display) {
    }

    /** A grouped count, used for the "top countries", "top versions" style summaries. */
    public record Tally(String key, long count, String display) {
    }

    /** Everything known about one account, assembled for the lookup command and the API. */
    public record FullProfile(PlayerProfile profile,
                              List<NameHistoryEntry> names,
                              List<PlayerAddress> addresses,
                              List<AddressRecord> geo,
                              List<ServerPlaytime> servers,
                              List<SessionRecord> recentSessions,
                              List<PlaceholderValue> placeholders,
                              List<ActivityCell> activity,
                              List<DailyActivity> daily,
                              List<AltAccount> alts,
                              List<Alert> alerts,
                              List<Note> notes,
                              List<Tag> tags) {
    }

    /** Proxy-wide totals for the summary command and the metrics endpoint. */
    public record Overview(long players, long sessions, long events, long addresses,
                           long totalPlaytime, long chatMessages, long commands, long alerts,
                           int peakOnline, long peakOnlineAt, long databaseBytes,
                           long newPlayersToday, long activeToday) {
    }
}
