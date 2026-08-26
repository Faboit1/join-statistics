package dev.faboit.joinstats.velocity.storage.dao;

import dev.faboit.joinstats.velocity.geo.GeoData;
import dev.faboit.joinstats.velocity.storage.Database;
import dev.faboit.joinstats.velocity.storage.WriteQueue;
import dev.faboit.joinstats.velocity.storage.model.AddressRecord;
import dev.faboit.joinstats.velocity.storage.model.Records;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The address registry, the account-to-address links, and the shared-address search. */
public final class AddressDao {

    private final Database database;
    private final WriteQueue writes;

    public AddressDao(Database database, WriteQueue writes) {
        this.database = database;
        this.writes = writes;
    }

    // ------------------------------------------------------------------ writes

    /** Records that an account connected from an address, creating both rows if needed. */
    public void recordConnection(UUID uuid, String address, String subnet, long now) {
        writes.execute("""
                INSERT INTO js_addresses (address, subnet, first_seen, last_seen, hits)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT(address) DO UPDATE SET
                    last_seen = excluded.last_seen,
                    hits      = js_addresses.hits + 1,
                    subnet    = COALESCE(js_addresses.subnet, excluded.subnet)
                """, address, subnet, now, now);

        writes.execute("""
                INSERT INTO js_player_addresses (uuid, address, subnet, first_seen, last_seen, connections)
                VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(uuid, address) DO UPDATE SET
                    last_seen   = excluded.last_seen,
                    connections = js_player_addresses.connections + 1,
                    subnet      = COALESCE(js_player_addresses.subnet, excluded.subnet)
                """, uuid, address, subnet, now, now);
    }

    /** Attributes a finished session's playtime to the address it was played from. */
    public void addPlaytime(UUID uuid, String address, long millis) {
        if (millis <= 0) {
            return;
        }
        writes.execute("UPDATE js_player_addresses SET playtime = playtime + ? "
                + "WHERE uuid = ? AND address = ?", millis, uuid, address);
    }

    /** Stores a resolved geolocation against an address. */
    public void updateGeo(String address, GeoData geo, long now) {
        writes.execute("""
                UPDATE js_addresses SET
                    hostname     = COALESCE(?, hostname),
                    continent    = ?, country = ?, country_code = ?, region = ?, city = ?,
                    postal       = ?, latitude = ?, longitude = ?, accuracy_km = ?, timezone = ?,
                    isp          = ?, organisation = ?, asn = ?, as_name = ?,
                    is_mobile    = ?, is_proxy = ?, is_hosting = ?, is_tor = ?,
                    geo_source   = ?, geo_updated = ?
                 WHERE address = ?
                """,
                geo.hostname(), geo.continent(), geo.country(), geo.countryCode(), geo.region(),
                geo.city(), geo.postal(), geo.latitude(), geo.longitude(), geo.accuracyKm(),
                geo.timezone(), geo.isp(), geo.organisation(), geo.asn(), geo.asName(),
                geo.mobile(), geo.proxy(), geo.hosting(), geo.tor(), geo.source(), now, address);
    }

    /** Stores a reverse-DNS result without touching the rest of the geolocation. */
    public void updateHostname(String address, String hostname) {
        writes.execute("UPDATE js_addresses SET hostname = ? WHERE address = ?", hostname, address);
    }

    // ------------------------------------------------------------------ reads

    public CompletableFuture<Optional<AddressRecord>> find(String address) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_addresses WHERE address = ?")) {
                Database.bind(statement, address);
                List<AddressRecord> rows = Database.list(statement, AddressDao::mapAddress);
                return rows.isEmpty() ? Optional.<AddressRecord>empty() : Optional.of(rows.get(0));
            }
        });
    }

    /** Reads an address on the calling thread; used by the geolocation worker. */
    public Optional<AddressRecord> findNow(String address) {
        return database.queryNow(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM js_addresses WHERE address = ?")) {
                Database.bind(statement, address);
                List<AddressRecord> rows = Database.list(statement, AddressDao::mapAddress);
                return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
            }
        });
    }

    public CompletableFuture<List<Records.PlayerAddress>> addressesOf(UUID uuid) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT address, subnet, first_seen, last_seen, connections, playtime
                      FROM js_player_addresses WHERE uuid = ? ORDER BY last_seen DESC
                    """)) {
                Database.bind(statement, uuid);
                return Database.list(statement, rows -> new Records.PlayerAddress(
                        rows.getString("address"), rows.getString("subnet"),
                        rows.getLong("first_seen"), rows.getLong("last_seen"),
                        rows.getInt("connections"), rows.getLong("playtime")));
            }
        });
    }

    /** Full geolocation rows for every address an account has used. */
    public CompletableFuture<List<AddressRecord>> geoFor(UUID uuid) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT a.* FROM js_addresses a
                      JOIN js_player_addresses pa ON pa.address = a.address
                     WHERE pa.uuid = ? ORDER BY pa.last_seen DESC
                    """)) {
                Database.bind(statement, uuid);
                return Database.list(statement, AddressDao::mapAddress);
            }
        });
    }

    /** Every account that has connected from a given address. */
    public CompletableFuture<List<Records.AltAccount>> playersOn(String address) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT pa.uuid, p.username, pa.address, pa.last_seen
                      FROM js_player_addresses pa
                      JOIN js_players p ON p.uuid = pa.uuid
                     WHERE pa.address = ? ORDER BY pa.last_seen DESC
                    """)) {
                Database.bind(statement, address);
                return Database.list(statement, rows -> new Records.AltAccount(
                        UUID.fromString(rows.getString("uuid")), rows.getString("username"),
                        rows.getString("address"), rows.getLong("last_seen"), 1, true));
            }
        });
    }

    /**
     * Finds other accounts that have shared an address with this one.
     *
     * <p>When {@code includeSubnet} is set, IPv6 addresses also match on their /64 prefix. That
     * matters because a residential IPv6 allocation rotates its interface identifier while the
     * prefix stays put — without it, alt detection simply stops working for IPv6 households.
     *
     * <p>{@code since} bounds how far back a shared address counts. Addresses get reassigned, so
     * a match from two years ago says nothing about the same person being behind both accounts.
     */
    public CompletableFuture<List<Records.AltAccount>> findAlts(UUID uuid, long since,
                                                                boolean includeSubnet, int limit) {
        return database.query(connection -> {
            List<Records.AltAccount> out = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT other.uuid            AS uuid,
                           p.username            AS username,
                           other.address         AS address,
                           MAX(other.last_seen)  AS last_shared,
                           COUNT(DISTINCT other.address) AS shared
                      FROM js_player_addresses mine
                      JOIN js_player_addresses other
                        ON other.address = mine.address AND other.uuid <> mine.uuid
                      JOIN js_players p ON p.uuid = other.uuid
                     WHERE mine.uuid = ? AND other.last_seen >= ? AND mine.last_seen >= ?
                     GROUP BY other.uuid
                     ORDER BY last_shared DESC
                     LIMIT ?
                    """)) {
                Database.bind(statement, uuid, since, since, limit);
                out.addAll(Database.list(statement, rows -> new Records.AltAccount(
                        UUID.fromString(rows.getString("uuid")), rows.getString("username"),
                        rows.getString("address"), rows.getLong("last_shared"),
                        rows.getInt("shared"), true)));
            }

            if (!includeSubnet) {
                return out;
            }

            // Second pass over the /64, skipping accounts the exact-match pass already returned.
            List<UUID> seen = new ArrayList<>(out.size());
            for (Records.AltAccount alt : out) {
                seen.add(alt.uuid());
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT other.uuid            AS uuid,
                           p.username            AS username,
                           other.subnet          AS address,
                           MAX(other.last_seen)  AS last_shared,
                           COUNT(DISTINCT other.subnet) AS shared
                      FROM js_player_addresses mine
                      JOIN js_player_addresses other
                        ON other.subnet = mine.subnet AND other.uuid <> mine.uuid
                      JOIN js_players p ON p.uuid = other.uuid
                     WHERE mine.uuid = ? AND mine.subnet IS NOT NULL
                       AND mine.subnet <> mine.address
                       AND other.last_seen >= ? AND mine.last_seen >= ?
                     GROUP BY other.uuid
                     ORDER BY last_shared DESC
                     LIMIT ?
                    """)) {
                Database.bind(statement, uuid, since, since, limit);
                for (Records.AltAccount alt : Database.list(statement, rows ->
                        new Records.AltAccount(
                                UUID.fromString(rows.getString("uuid")), rows.getString("username"),
                                rows.getString("address"), rows.getLong("last_shared"),
                                rows.getInt("shared"), false))) {
                    if (!seen.contains(alt.uuid())) {
                        out.add(alt);
                    }
                }
            }
            return out;
        });
    }

    /** Addresses whose geolocation is missing or older than the cutoff. */
    public CompletableFuture<List<String>> staleGeo(long cutoff, int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT address FROM js_addresses WHERE geo_updated < ? "
                            + "ORDER BY last_seen DESC LIMIT ?")) {
                Database.bind(statement, cutoff, limit);
                return Database.list(statement, rows -> rows.getString(1));
            }
        });
    }

    /** Groups stored addresses by country, for the network summary. */
    public CompletableFuture<List<Records.Tally>> countries(int limit) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(a.country, 'unknown') AS k, COUNT(DISTINCT pa.uuid) AS c
                      FROM js_addresses a
                      JOIN js_player_addresses pa ON pa.address = a.address
                     GROUP BY k ORDER BY c DESC LIMIT ?
                    """)) {
                Database.bind(statement, limit);
                return Database.list(statement, rows -> new Records.Tally(
                        rows.getString("k"), rows.getLong("c"), rows.getString("k")));
            }
        });
    }

    static AddressRecord mapAddress(ResultSet rows) throws SQLException {
        double latitude = rows.getDouble("latitude");
        Double lat = rows.wasNull() ? null : latitude;
        double longitude = rows.getDouble("longitude");
        Double lon = rows.wasNull() ? null : longitude;
        int accuracy = rows.getInt("accuracy_km");
        Integer accuracyKm = rows.wasNull() ? null : accuracy;
        int asn = rows.getInt("asn");
        Integer asNumber = rows.wasNull() ? null : asn;

        return new AddressRecord(
                rows.getString("address"),
                rows.getString("subnet"),
                rows.getLong("first_seen"),
                rows.getLong("last_seen"),
                rows.getLong("hits"),
                rows.getString("hostname"),
                rows.getString("continent"),
                rows.getString("country"),
                rows.getString("country_code"),
                rows.getString("region"),
                rows.getString("city"),
                rows.getString("postal"),
                lat, lon, accuracyKm,
                rows.getString("timezone"),
                rows.getString("isp"),
                rows.getString("organisation"),
                asNumber,
                rows.getString("as_name"),
                rows.getInt("is_mobile") != 0,
                rows.getInt("is_proxy") != 0,
                rows.getInt("is_hosting") != 0,
                rows.getInt("is_tor") != 0,
                rows.getString("geo_source"),
                rows.getLong("geo_updated"));
    }
}
