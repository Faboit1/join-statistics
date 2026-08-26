package dev.faboit.joinstats.velocity.geo;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.AnonymousIpResponse;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.Subdivision;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Local lookups against MaxMind's {@code .mmdb} files.
 *
 * <p>Three databases are consulted independently, because the free GeoLite2 tier splits the data
 * across them: City has the place, ASN has the network operator, and Anonymous-IP has the
 * VPN/proxy flags. Any of them may be absent; whatever is present contributes.
 *
 * <p>The files are memory-mapped through a {@link CHMCache}, so a lookup is a handful of
 * pointer hops with no I/O and no rate limit — which is why this provider is tried first.
 */
public final class MaxMindResolver implements GeoResolver {

    private final Logger logger;
    private final Path dataDirectory;
    private final PluginConfig.MaxMind settings;

    private volatile Reader city;
    private volatile Reader asn;
    private volatile Reader anonymous;

    public MaxMindResolver(Path dataDirectory, PluginConfig.MaxMind settings, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.settings = settings;
        this.logger = logger;
        this.city = open(settings.cityDatabase, "city");
        this.asn = open(settings.asnDatabase, "ASN");
        this.anonymous = open(settings.anonymousDatabase, "anonymous-IP");
    }

    @Override
    public String name() {
        return "maxmind";
    }

    @Override
    public boolean available() {
        return city != null || asn != null || anonymous != null;
    }

    @Override
    public GeoData resolve(String address) throws Exception {
        if (settings.watchForUpdates) {
            reloadIfChanged();
        }
        InetAddress parsed = InetAddress.getByName(address);
        GeoData result = GeoData.empty(name());

        Reader cityReader = city;
        if (cityReader != null) {
            Optional<CityResponse> response = cityReader.reader.tryCity(parsed);
            if (response.isPresent()) {
                result = fromCity(response.get());
            }
        }

        Reader asnReader = asn;
        if (asnReader != null) {
            Optional<AsnResponse> response = asnReader.reader.tryAsn(parsed);
            if (response.isPresent()) {
                AsnResponse asnData = response.get();
                Long number = asnData.getAutonomousSystemNumber();
                result = result.merge(new GeoData(null, null, null, null, null, null, null, null,
                        null, null, null, asnData.getAutonomousSystemOrganization(),
                        asnData.getAutonomousSystemOrganization(),
                        number == null ? null : number.intValue(),
                        asnData.getAutonomousSystemOrganization(),
                        false, false, false, false, name()));
            }
        }

        Reader anonymousReader = anonymous;
        if (anonymousReader != null) {
            Optional<AnonymousIpResponse> response = anonymousReader.reader.tryAnonymousIp(parsed);
            if (response.isPresent()) {
                AnonymousIpResponse flags = response.get();
                result = result.merge(new GeoData(null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null,
                        false,
                        flags.isAnonymousVpn() || flags.isPublicProxy() || flags.isResidentialProxy(),
                        flags.isHostingProvider(),
                        flags.isTorExitNode(),
                        name()));
            }
        }

        return result;
    }

    private GeoData fromCity(CityResponse response) {
        Subdivision subdivision = response.getMostSpecificSubdivision();
        var location = response.getLocation();
        var traits = response.getTraits();

        Long asNumber = traits == null ? null : traits.getAutonomousSystemNumber();
        return new GeoData(
                traits == null ? null : blankToNull(traits.getDomain()),
                response.getContinent() == null ? null : response.getContinent().getName(),
                response.getCountry() == null ? null : response.getCountry().getName(),
                response.getCountry() == null ? null : response.getCountry().getIsoCode(),
                subdivision == null ? null : subdivision.getName(),
                response.getCity() == null ? null : response.getCity().getName(),
                response.getPostal() == null ? null : response.getPostal().getCode(),
                location == null ? null : location.getLatitude(),
                location == null ? null : location.getLongitude(),
                location == null ? null : location.getAccuracyRadius(),
                location == null ? null : location.getTimeZone(),
                traits == null ? null : blankToNull(traits.getIsp()),
                traits == null ? null : blankToNull(traits.getOrganization()),
                asNumber == null ? null : asNumber.intValue(),
                traits == null ? null : blankToNull(traits.getAutonomousSystemOrganization()),
                false,
                traits != null && (traits.isAnonymousVpn() || traits.isPublicProxy()
                        || traits.isResidentialProxy()),
                traits != null && traits.isHostingProvider(),
                traits != null && traits.isTorExitNode(),
                name());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Picks up a database replaced on disk — GeoLite2 files are refreshed twice a week. */
    private void reloadIfChanged() {
        city = reloadIfStale(city, settings.cityDatabase, "city");
        asn = reloadIfStale(asn, settings.asnDatabase, "ASN");
        anonymous = reloadIfStale(anonymous, settings.anonymousDatabase, "anonymous-IP");
    }

    private Reader reloadIfStale(Reader current, String configured, String label) {
        Path path = databasePath(configured);
        if (path == null) {
            return current;
        }
        long modified;
        try {
            modified = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (IOException e) {
            return current;
        }
        if (modified == 0L) {
            return current;
        }
        if (current != null && current.lastModified == modified) {
            return current;
        }
        Reader replacement = open(configured, label);
        if (replacement == null) {
            return current;
        }
        if (current != null) {
            try {
                current.reader.close();
            } catch (IOException e) {
                logger.debug("Could not close the previous {} database.", label, e);
            }
        }
        logger.info("Reloaded the MaxMind {} database.", label);
        return replacement;
    }

    private Path databasePath(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path path = Path.of(configured);
        return path.isAbsolute() ? path : dataDirectory.resolve(path);
    }

    private Reader open(String configured, String label) {
        Path path = databasePath(configured);
        if (path == null || !Files.isReadable(path)) {
            return null;
        }
        try {
            DatabaseReader reader = new DatabaseReader.Builder(path.toFile())
                    .withCache(new CHMCache())
                    .locales(List.of("en"))
                    .build();
            logger.info("Loaded the MaxMind {} database from {}.", label, path.getFileName());
            return new Reader(reader, Files.getLastModifiedTime(path).toMillis());
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not open the MaxMind {} database at {}: {}",
                    label, path, e.getMessage());
            return null;
        }
    }

    @Override
    public void close() {
        for (Reader reader : new Reader[]{city, asn, anonymous}) {
            if (reader != null) {
                try {
                    reader.reader.close();
                } catch (IOException e) {
                    logger.debug("Failed to close a MaxMind database.", e);
                }
            }
        }
        city = null;
        asn = null;
        anonymous = null;
    }

    private record Reader(DatabaseReader reader, long lastModified) {
    }
}
