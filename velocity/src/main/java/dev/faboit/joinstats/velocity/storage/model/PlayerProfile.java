package dev.faboit.joinstats.velocity.storage.model;

import java.util.UUID;

/** The accumulated profile for one account — a row of {@code js_players}. */
public record PlayerProfile(
        UUID uuid,
        String username,
        long firstSeen,
        long lastSeen,
        long lastQuit,
        long playtime,
        long idleTime,
        int sessions,
        int connections,
        long longestSession,
        int kicks,
        int chatMessages,
        int commands,
        int serverSwitches,
        String lastAddress,
        String lastCountry,
        String lastCountryCode,
        String lastCity,
        String lastServer,
        int firstProtocol,
        int lastProtocol,
        String lastVersion,
        String lastBrand,
        String lastLocale,
        boolean onlineMode,
        long pingTotal,
        long pingSamples,
        int pingBest,
        int pingWorst) {

    /** Mean playtime per completed session, or zero before the first session ends. */
    public long averageSession() {
        return sessions <= 0 ? 0L : playtime / sessions;
    }

    /** Mean measured latency in milliseconds, or {@code -1} when never sampled. */
    public int averagePing() {
        return pingSamples <= 0 ? -1 : (int) (pingTotal / pingSamples);
    }

    /** Fraction of tracked playtime spent idle, in the range 0..1. */
    public double idleRatio() {
        return playtime <= 0 ? 0.0 : Math.min(1.0, (double) idleTime / (double) playtime);
    }
}
