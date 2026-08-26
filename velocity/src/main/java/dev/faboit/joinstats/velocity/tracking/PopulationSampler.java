package dev.faboit.joinstats.velocity.tracking;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.geo.GeoData;
import dev.faboit.joinstats.velocity.geo.GeoService;
import dev.faboit.joinstats.velocity.storage.dao.AnnotationDao;
import dev.faboit.joinstats.velocity.storage.dao.PopulationDao;
import dev.faboit.joinstats.velocity.util.Addresses;
import dev.faboit.joinstats.velocity.util.Versions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Samples how many players are online, at whatever resolution the operator asks for.
 *
 * <p>At the shipped one-second interval this is 86 400 rows a day — trivially small next to the
 * event log, and the only way to answer questions like "how many people were actually on when the
 * proxy stalled at 19:41". The rollup job folds those into minute, hour and day buckets so a
 * month-wide graph reads a few hundred rows instead of two and a half million, and retention
 * prunes the raw samples once the rollups exist.
 */
public final class PopulationSampler {

    private final ProxyServer proxy;
    private final PopulationDao population;
    private final AnnotationDao annotations;
    private final GeoService geo;
    private final Supplier<PluginConfig> config;
    private final PeakListener peakListener;

    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicLong samplesTaken = new AtomicLong();
    private final AtomicLong lastRollupThrough = new AtomicLong();

    public PopulationSampler(ProxyServer proxy, PopulationDao population, AnnotationDao annotations,
                             GeoService geo, Supplier<PluginConfig> config,
                             PeakListener peakListener) {
        this.proxy = proxy;
        this.population = population;
        this.annotations = annotations;
        this.geo = geo;
        this.config = config;
        this.peakListener = peakListener;
    }

    /** Seeds the in-memory peak from storage, so a restart cannot "beat" an older record. */
    public void primePeak(int storedPeak) {
        peak.accumulateAndGet(storedPeak, Math::max);
    }

    public int peak() {
        return peak.get();
    }

    public long samplesTaken() {
        return samplesTaken.get();
    }

    /** Takes one sample. Cheap enough to run every second on the scheduler. */
    public void sample(long now) {
        PluginConfig settings = config.get();
        if (!settings.population.enabled) {
            return;
        }

        int total = proxy.getPlayerCount();
        population.sample(now, total);
        samplesTaken.incrementAndGet();

        if (settings.population.perServer) {
            Map<String, Integer> perServer = new HashMap<>();
            for (RegisteredServer server : proxy.getAllServers()) {
                String name = server.getServerInfo().getName();
                if (PluginConfig.containsIgnoreCase(settings.tracking.ignoredServers, name)) {
                    continue;
                }
                perServer.put(name, server.getPlayersConnected().size());
            }
            population.breakdown(now, PopulationDao.SCOPE_SERVER, perServer);
        }

        if (settings.population.perVersion || settings.population.perCountry) {
            Map<String, Integer> versions = new HashMap<>();
            Map<String, Integer> countries = new HashMap<>();
            for (Player player : proxy.getAllPlayers()) {
                if (settings.population.perVersion) {
                    versions.merge(Versions.name(player.getProtocolVersion()), 1, Integer::sum);
                }
                if (settings.population.perCountry) {
                    countries.merge(countryOf(player), 1, Integer::sum);
                }
            }
            if (settings.population.perVersion) {
                population.breakdown(now, PopulationDao.SCOPE_VERSION, versions);
            }
            if (settings.population.perCountry) {
                population.breakdown(now, PopulationDao.SCOPE_COUNTRY, countries);
            }
        }

        int previousPeak = peak.getAndAccumulate(total, Math::max);
        if (total > previousPeak) {
            annotations.recordHighWater("peak_online", total, now, null);
            if (peakListener != null) {
                peakListener.onNewPeak(total, previousPeak, now);
            }
        }
    }

    /**
     * Reads a country from the geolocation cache only.
     *
     * <p>A sample must never block: triggering a lookup here would put an HTTP request on the
     * one-second path, and an address that is not yet cached is simply counted as unknown until
     * the join-time lookup fills it in.
     */
    private String countryOf(Player player) {
        String address = Addresses.ipOf(player.getRemoteAddress());
        GeoData cached = geo.cached(address);
        if (cached == null || cached.countryCode() == null) {
            return "unknown";
        }
        return cached.countryCode();
    }

    /**
     * Folds recent raw samples into every configured bucket width.
     *
     * <p>Re-aggregates a window slightly wider than the time since the last run, because the
     * bucket the previous run ended inside was still filling up. The rollup upsert replaces a
     * bucket rather than adding to it, so recomputing an overlapping window is idempotent.
     */
    public void rollup(long now) {
        PluginConfig settings = config.get();
        if (!settings.population.enabled) {
            return;
        }
        long from = lastRollupThrough.get();
        for (Duration bucket : settings.population.rollupBuckets()) {
            long width = bucket.toMillis();
            if (width <= 0) {
                continue;
            }
            // Start from the beginning of the bucket the previous run stopped in, or one bucket
            // back on the very first run.
            long start = from == 0 ? now - width * 2 : Math.floorDiv(from, width) * width;
            population.rollup(width, start, now);
        }
        lastRollupThrough.set(now);
    }

    /** Notified when the concurrent player count sets a new record. */
    @FunctionalInterface
    public interface PeakListener {
        void onNewPeak(int count, int previousPeak, long at);
    }
}
