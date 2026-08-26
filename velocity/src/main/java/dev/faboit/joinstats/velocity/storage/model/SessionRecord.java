package dev.faboit.joinstats.velocity.storage.model;

import java.util.UUID;

/** One session — a stretch of presence, possibly spanning several TCP connections. */
public record SessionRecord(
        long id,
        UUID uuid,
        String username,
        long startedAt,
        long endedAt,
        long heartbeatAt,
        long duration,
        long gapTime,
        long idleTime,
        int connections,
        boolean open,
        boolean crashed,
        String address,
        String countryCode,
        String city,
        int protocol,
        String versionName,
        String brand,
        String locale,
        String virtualHost,
        boolean onlineMode,
        String firstServer,
        String lastServer,
        int serversSeen,
        int chatMessages,
        int commands,
        int kicks,
        String quitReason,
        long pingTotal,
        long pingSamples) {

    /** Wall-clock span from first connect to final disconnect, gaps included. */
    public long wallDuration() {
        long end = endedAt > 0 ? endedAt : heartbeatAt;
        return Math.max(0L, end - startedAt);
    }

    /** True when this session absorbed at least one reconnect inside the grace window. */
    public boolean wasResumed() {
        return connections > 1;
    }

    public int averagePing() {
        return pingSamples <= 0 ? -1 : (int) (pingTotal / pingSamples);
    }
}
