package dev.faboit.joinstats.velocity.geo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.storage.dao.AddressDao;
import dev.faboit.joinstats.velocity.storage.model.AddressRecord;
import dev.faboit.joinstats.velocity.util.Addresses;
import dev.faboit.joinstats.velocity.util.Durations;
import dev.faboit.joinstats.velocity.util.Threads;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * The front door for geolocation: cache, provider chain, and persistence.
 *
 * <p>Lookups never run on a Netty thread. A join hands the address here and continues; when an
 * answer arrives it is written to the address registry and the player's profile is topped up.
 * That ordering means the very first session for a brand-new address may be stored without a
 * country, which is the right trade — a login must not wait on a third-party HTTP call.
 */
public final class GeoService implements AutoCloseable {

    private final Logger logger;
    private final AddressDao addresses;
    private final List<GeoResolver> chain = new ArrayList<>();
    private final ExecutorService executor;
    private final Cache<String, GeoData> cache;
    private final PluginConfig.Geolocation settings;
    private final long ttlMillis;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public GeoService(Path dataDirectory, PluginConfig.Geolocation settings, AddressDao addresses,
                      Logger logger) {
        this.settings = settings;
        this.addresses = addresses;
        this.logger = logger;
        this.ttlMillis = Durations.parse(settings.cacheTtl, Duration.ofDays(7)).toMillis();

        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(128, settings.cacheSize))
                .expireAfterWrite(Math.max(60_000L, ttlMillis), TimeUnit.MILLISECONDS)
                .build();

        // A single thread: the HTTP provider is rate limited anyway, and serialising lookups
        // keeps a burst of joins from opening dozens of concurrent sockets.
        this.executor = Executors.newSingleThreadExecutor(Threads.factory("geo", logger));

        if (settings.enabled) {
            for (String provider : settings.providers) {
                GeoResolver resolver = switch (provider.toLowerCase(Locale.ROOT)) {
                    case "maxmind" -> new MaxMindResolver(dataDirectory, settings.maxmind, logger);
                    case "http" -> new HttpGeoResolver(settings.http, logger);
                    default -> null;
                };
                if (resolver == null) {
                    logger.warn("Ignoring unknown geolocation provider '{}'.", provider);
                } else if (resolver.available()) {
                    chain.add(resolver);
                } else {
                    logger.info("Geolocation provider '{}' is configured but not usable "
                            + "(no database file, or no endpoint); skipping it.", provider);
                    closeQuietly(resolver);
                }
            }
            if (chain.isEmpty()) {
                logger.warn("Geolocation is enabled but no provider is usable. Install a "
                        + "GeoLite2 database, or leave the http provider in the chain.");
            }
        }
    }

    public boolean enabled() {
        return settings.enabled && !chain.isEmpty();
    }

    /** The answer already in memory, if any. Safe on any thread; never triggers a lookup. */
    public GeoData cached(String address) {
        return cache.getIfPresent(address);
    }

    /**
     * Resolves an address, consulting the cache and then the stored row before any provider.
     *
     * <p>Always completes — a failure yields {@link GeoData#empty} rather than an exceptional
     * future, because every caller's fallback is "carry on without a country" and making each of
     * them write that out would only invite one to forget.
     */
    public CompletableFuture<GeoData> lookup(String address) {
        if (!enabled() || address == null || address.isBlank()) {
            return CompletableFuture.completedFuture(GeoData.empty("disabled"));
        }
        if (Addresses.isPrivate(address)) {
            return CompletableFuture.completedFuture(GeoData.empty("private"));
        }

        GeoData memo = cache.getIfPresent(address);
        if (memo != null && !settings.alwaysRefresh) {
            hits.incrementAndGet();
            return CompletableFuture.completedFuture(memo);
        }

        return CompletableFuture.supplyAsync(() -> {
            GeoData current = cache.getIfPresent(address);
            if (current != null && !settings.alwaysRefresh) {
                hits.incrementAndGet();
                return current;
            }

            if (!settings.alwaysRefresh) {
                GeoData stored = fromStorage(address);
                if (stored != null) {
                    hits.incrementAndGet();
                    cache.put(address, stored);
                    return stored;
                }
            }

            misses.incrementAndGet();
            GeoData resolved = runChain(address);
            cache.put(address, resolved);
            if (!resolved.isEmpty()) {
                addresses.updateGeo(address, resolved, System.currentTimeMillis());
            }
            return resolved;
        }, executor).exceptionally(error -> {
            failures.incrementAndGet();
            logger.debug("Geolocation for {} failed outright.", Addresses.mask(address), error);
            return GeoData.empty("error");
        });
    }

    /** Reads a previously stored answer, if it is still inside the configured TTL. */
    private GeoData fromStorage(String address) {
        try {
            AddressRecord record = addresses.findNow(address).orElse(null);
            if (record == null || record.geoUpdated() <= 0) {
                return null;
            }
            if (System.currentTimeMillis() - record.geoUpdated() > ttlMillis) {
                return null;
            }
            return new GeoData(record.hostname(), record.continent(), record.country(),
                    record.countryCode(), record.region(), record.city(), record.postal(),
                    record.latitude(), record.longitude(), record.accuracyKm(), record.timezone(),
                    record.isp(), record.organisation(), record.asn(), record.asName(),
                    record.mobile(), record.proxy(), record.hosting(), record.tor(),
                    record.geoSource());
        } catch (RuntimeException e) {
            logger.debug("Could not read the stored geolocation for {}.",
                    Addresses.mask(address), e);
            return null;
        }
    }

    /**
     * Walks the provider chain, merging partial answers.
     *
     * <p>It keeps going after a provider succeeds when that provider left gaps — a GeoLite2 City
     * hit with no ASN data is still worth completing from the next provider, and the merge only
     * fills nulls, so the first (most trusted) answer wins wherever the two overlap.
     */
    private GeoData runChain(String address) {
        GeoData accumulated = GeoData.empty("none");
        for (GeoResolver resolver : chain) {
            if (isComplete(accumulated)) {
                break;
            }
            try {
                GeoData answer = resolver.resolve(address);
                if (answer != null && !answer.isEmpty()) {
                    accumulated = accumulated.isEmpty() ? answer : accumulated.merge(answer);
                }
            } catch (HttpGeoResolver.RateLimitedException e) {
                logger.debug("Skipping provider {} for {}: {}", resolver.name(),
                        Addresses.mask(address), e.getMessage());
            } catch (Exception e) {
                failures.incrementAndGet();
                logger.debug("Provider {} failed for {}.", resolver.name(),
                        Addresses.mask(address), e);
            }
        }
        return accumulated;
    }

    /** True once we have everything the address registry has a column for. */
    private static boolean isComplete(GeoData data) {
        return data.countryCode() != null && data.city() != null && data.asn() != null;
    }

    /**
     * Resolves a reverse DNS name for an address, off the calling thread.
     *
     * <p>Kept separate from the main lookup because a PTR query against an address with no
     * record blocks for the resolver's full timeout, and nothing on the join path can afford to
     * wait for that even indirectly.
     */
    public CompletableFuture<String> reverseDns(String address) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                InetAddress parsed = InetAddress.getByName(address);
                String hostname = parsed.getCanonicalHostName();
                // getCanonicalHostName echoes the literal back when there is no PTR record.
                return hostname.equals(parsed.getHostAddress()) ? null : hostname;
            } catch (Exception e) {
                return null;
            }
        }, executor);
    }

    /** Refreshes the geolocation of addresses whose stored answer has aged out. */
    public void refreshStale(int limit) {
        if (!enabled()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - ttlMillis;
        addresses.staleGeo(cutoff, limit).thenAccept(stale -> {
            for (String address : stale) {
                if (!Addresses.isPrivate(address)) {
                    lookup(address);
                }
            }
        }).exceptionally(error -> {
            logger.debug("Could not list addresses needing a geolocation refresh.", error);
            return null;
        });
    }

    /** Cache and provider counters, for {@code /joinstats status}. */
    public Stats stats() {
        List<String> names = new ArrayList<>(chain.size());
        for (GeoResolver resolver : chain) {
            names.add(resolver.name());
        }
        return new Stats(names, cache.estimatedSize(), hits.get(), misses.get(), failures.get());
    }

    private void closeQuietly(GeoResolver resolver) {
        try {
            resolver.close();
        } catch (Exception e) {
            logger.debug("Failed to close geolocation provider {}.", resolver.name(), e);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        chain.forEach(this::closeQuietly);
        chain.clear();
        cache.invalidateAll();
    }

    /** A snapshot of geolocation activity. */
    public record Stats(List<String> providers, long cached, long hits, long misses,
                        long failures) {
    }
}
