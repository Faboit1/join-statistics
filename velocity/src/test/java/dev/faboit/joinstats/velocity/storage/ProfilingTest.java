package dev.faboit.joinstats.velocity.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.geo.GeoData;
import dev.faboit.joinstats.velocity.storage.dao.AddressDao;
import dev.faboit.joinstats.velocity.storage.dao.AnnotationDao;
import dev.faboit.joinstats.velocity.storage.dao.EventDao;
import dev.faboit.joinstats.velocity.storage.dao.MaintenanceDao;
import dev.faboit.joinstats.velocity.storage.dao.PlaceholderDao;
import dev.faboit.joinstats.velocity.storage.dao.PlayerDao;
import dev.faboit.joinstats.velocity.storage.model.PlayerProfile;
import dev.faboit.joinstats.velocity.storage.model.Records;
import dev.faboit.joinstats.velocity.util.Addresses;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Covers the profile, address and shared-address behaviour the lookup commands rest on. */
class ProfilingTest {

    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long NOW = 1_700_000_000_000L;

    @TempDir
    Path directory;

    private Database database;
    private WriteQueue writes;
    private PlayerDao players;
    private AddressDao addresses;
    private PlaceholderDao placeholders;
    private AnnotationDao annotations;
    private MaintenanceDao maintenance;
    private EventDao events;

    @BeforeEach
    void setUp() throws Exception {
        PluginConfig.Storage settings = new PluginConfig.Storage();
        settings.file = "profiling.db";
        var logger = LoggerFactory.getLogger(ProfilingTest.class);
        database = new Database(directory, settings, logger);
        writes = new WriteQueue(database, logger, 200, 50L, 0);
        players = new PlayerDao(database, writes);
        addresses = new AddressDao(database, writes);
        placeholders = new PlaceholderDao(database, writes);
        annotations = new AnnotationDao(database, writes);
        maintenance = new MaintenanceDao(database, writes);
        events = new EventDao(database, writes);
    }

    @AfterEach
    void tearDown() {
        writes.close();
        database.close();
    }

    // ------------------------------------------------------------------ profiles

    @Test
    void firstSeenIsNeverRewrittenByALaterLogin() {
        UUID player = UUID.randomUUID();
        login(player, "Alice", NOW, "203.0.113.7");
        login(player, "Alice", NOW + DAY, "203.0.113.7");
        login(player, "Alice", NOW + 2 * DAY, "203.0.113.7");
        settle();

        PlayerProfile profile = players.find(player).join().orElseThrow();
        assertEquals(NOW, profile.firstSeen(), "the first sighting is history, not a live value");
        assertEquals(NOW + 2 * DAY, profile.lastSeen());
        assertEquals(3, profile.connections());
    }

    @Test
    void nameHistoryTracksEveryUsernameTheAccountHasUsed() {
        UUID player = UUID.randomUUID();
        login(player, "OldName", NOW, "203.0.113.7");
        login(player, "NewName", NOW + DAY, "203.0.113.7");
        login(player, "NewName", NOW + 2 * DAY, "203.0.113.7");
        settle();

        List<Records.NameHistoryEntry> history = players.nameHistory(player).join();
        assertEquals(2, history.size());
        assertEquals("NewName", history.get(0).username(), "most recent first");
        assertEquals(2, history.get(0).connections());
        assertEquals("OldName", history.get(1).username());

        // A lookup by a name they no longer use must still find them.
        assertEquals(player, players.findByName("oldname").join().orElseThrow().uuid());
        assertEquals("NewName", players.find(player).join().orElseThrow().username());
    }

    @Test
    void lookupIsCaseInsensitive() {
        UUID player = UUID.randomUUID();
        login(player, "MixedCase", NOW, "203.0.113.7");
        settle();

        assertTrue(players.findByName("mixedcase").join().isPresent());
        assertTrue(players.findByName("MIXEDCASE").join().isPresent());
        assertTrue(players.resolve(player.toString()).join().isPresent());
        assertTrue(players.resolve(player.toString().replace("-", "")).join().isPresent(),
                "an undashed UUID is still a UUID");
    }

    @Test
    void searchDoesNotTreatUnderscoresAsWildcards() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        login(first, "Some_Name", NOW, "203.0.113.7");
        login(second, "SomeXName", NOW, "203.0.113.8");
        settle();

        List<PlayerProfile> found = players.search("Some_Name", 10).join();
        assertEquals(1, found.size(), "LIKE metacharacters in a name must be escaped");
        assertEquals("Some_Name", found.get(0).username());
    }

    @Test
    void latencyStatisticsTrackBestAndWorstSeparatelyFromTheMean() {
        UUID player = UUID.randomUUID();
        login(player, "Pingy", NOW, "203.0.113.7");
        players.recordPing(player, 40);
        players.recordPing(player, 20);
        players.recordPing(player, 120);
        settle();

        PlayerProfile profile = players.find(player).join().orElseThrow();
        assertEquals(60, profile.averagePing());
        assertEquals(20, profile.pingBest(), "the first sample must not become a floor of zero");
        assertEquals(120, profile.pingWorst());
    }

    // ------------------------------------------------------------------ shared addresses

    @Test
    void findsAccountsThatHaveUsedTheSameAddress() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();

        login(first, "Sibling1", NOW, "203.0.113.7");
        login(second, "Sibling2", NOW, "203.0.113.7");
        login(unrelated, "Stranger", NOW, "198.51.100.4");
        settle();

        List<Records.AltAccount> alts = addresses.findAlts(first, NOW - DAY, false, 10).join();
        assertEquals(1, alts.size());
        assertEquals("Sibling2", alts.get(0).username());
        assertTrue(alts.get(0).exactMatch());

        assertTrue(addresses.findAlts(unrelated, NOW - DAY, false, 10).join().isEmpty());
    }

    @Test
    void ignoresSharedAddressesOlderThanTheWindow() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        long longAgo = NOW - 400 * DAY;

        login(first, "Now", NOW, "203.0.113.7");
        login(second, "LongGone", longAgo, "203.0.113.7");
        settle();

        // Addresses get reassigned; a match from over a year ago says nothing.
        assertTrue(addresses.findAlts(first, NOW - 90 * DAY, false, 10).join().isEmpty());
        assertEquals(1, addresses.findAlts(first, longAgo - DAY, false, 10).join().size());
    }

    @Test
    void matchesIpv6HouseholdsOnTheirPrefixWhenAsked() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // Same /64, different interface identifiers — one household after a prefix rotation.
        String a = "2001:db8:1:2:aaaa:bbbb:cccc:dddd";
        String b = "2001:db8:1:2:1111:2222:3333:4444";

        loginV6(first, "Household1", NOW, a);
        loginV6(second, "Household2", NOW, b);
        settle();

        assertTrue(addresses.findAlts(first, NOW - DAY, false, 10).join().isEmpty(),
                "the exact addresses differ, so an exact-only search finds nothing");

        List<Records.AltAccount> bySubnet = addresses.findAlts(first, NOW - DAY, true, 10).join();
        assertEquals(1, bySubnet.size());
        assertEquals("Household2", bySubnet.get(0).username());
        assertFalse(bySubnet.get(0).exactMatch(), "a subnet match must be reported as weaker");
    }

    @Test
    void doesNotReportTheSameAccountTwiceAcrossBothMatchPasses() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String shared = "2001:db8:1:2:aaaa:bbbb:cccc:dddd";

        loginV6(first, "One", NOW, shared);
        loginV6(second, "Two", NOW, shared);
        settle();

        List<Records.AltAccount> alts = addresses.findAlts(first, NOW - DAY, true, 10).join();
        assertEquals(1, alts.size(), "an exact match must not also be listed as a subnet match");
        assertTrue(alts.get(0).exactMatch());
    }

    @Test
    void storesAndReadsBackGeolocation() {
        UUID player = UUID.randomUUID();
        login(player, "Traveller", NOW, "203.0.113.7");
        settle();

        addresses.updateGeo("203.0.113.7", new GeoData(null, "Europe", "Germany", "DE", "Berlin",
                "Berlin", "10115", 52.52, 13.405, 20, "Europe/Berlin", "Example ISP",
                "Example Org", 64496, "EXAMPLE-AS", false, true, false, false, "test"), NOW);
        settle();

        var record = addresses.find("203.0.113.7").join().orElseThrow();
        assertEquals("Germany", record.country());
        assertEquals("Berlin, Germany", record.describeLocation());
        assertEquals(64496, record.asn());
        assertTrue(record.anonymised());
        assertEquals("VPN or proxy", record.networkKind());
    }

    // ------------------------------------------------------------------ erasure

    @Test
    void forgettingAnAccountLeavesNothingBehind() {
        UUID player = UUID.randomUUID();
        UUID neighbour = UUID.randomUUID();

        login(player, "Erased", NOW, "203.0.113.7");
        login(neighbour, "Kept", NOW, "203.0.113.7");
        login(neighbour, "Kept", NOW, "198.51.100.9");
        events.record(NOW, "login", player, "Erased", "203.0.113.7", "lobby", null, null, null,
                null);
        events.recordChat(NOW, player, "Erased", "lobby", "hello", 5, false);
        placeholders.store(player, "%vault_eco_balance%", "100", "lobby", NOW);
        annotations.addNote(NOW, player, "staff", null, "a note");
        annotations.addTag(player, "watched", NOW, "staff");
        annotations.raise(NOW, "vpn-detected", "warning", player, "Erased", "message", null);
        settle();

        int deleted = maintenance.forget(player).join();
        settle();
        assertTrue(deleted > 0);

        assertTrue(players.find(player).join().isEmpty());
        assertTrue(players.nameHistory(player).join().isEmpty());
        assertTrue(addresses.addressesOf(player).join().isEmpty());
        assertTrue(events.forPlayer(player, 10, 0).join().isEmpty());
        assertTrue(events.chatOf(player, 10, 0).join().isEmpty());
        assertTrue(placeholders.current(player).join().isEmpty());
        assertTrue(annotations.notesFor(player, 10).join().isEmpty());
        assertTrue(annotations.tagsFor(player).join().isEmpty());
        assertTrue(annotations.alertsFor(player, 10).join().isEmpty());

        // The neighbour is untouched, and the address they still use survives.
        assertTrue(players.find(neighbour).join().isPresent());
        assertTrue(addresses.find("203.0.113.7").join().isPresent());
    }

    @Test
    void erasureDropsAddressesNoOneElseUses() {
        UUID player = UUID.randomUUID();
        login(player, "Solo", NOW, "203.0.113.99");
        settle();
        assertTrue(addresses.find("203.0.113.99").join().isPresent());

        maintenance.forget(player).join();
        settle();
        assertTrue(addresses.find("203.0.113.99").join().isEmpty(),
                "an address only this account ever used must not survive their erasure");
    }

    // ------------------------------------------------------------------ placeholders

    @Test
    void placeholderValuesUpsertRatherThanAccumulate() {
        UUID player = UUID.randomUUID();
        login(player, "Rich", NOW, "203.0.113.7");
        placeholders.store(player, "%vault_eco_balance%", "100", "survival", NOW);
        placeholders.store(player, "%vault_eco_balance%", "250", "survival", NOW + 1000);
        placeholders.appendHistory(player, "%vault_eco_balance%", "100", "survival", NOW);
        placeholders.appendHistory(player, "%vault_eco_balance%", "250", "survival", NOW + 1000);
        settle();

        List<Records.PlaceholderValue> current = placeholders.current(player).join();
        assertEquals(1, current.size(), "the current value is one row, not an append log");
        assertEquals("250", current.get(0).value());
        assertEquals(2, placeholders.history(player, "%vault_eco_balance%", 10).join().size());
    }

    @Test
    void ranksNumericPlaceholdersAndSkipsUnresolvedOnes() {
        UUID rich = UUID.randomUUID();
        UUID poor = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        login(rich, "Rich", NOW, "203.0.113.1");
        login(poor, "Poor", NOW, "203.0.113.2");
        login(broken, "Broken", NOW, "203.0.113.3");

        placeholders.store(rich, "%balance%", "9001", "survival", NOW);
        placeholders.store(poor, "%balance%", "12", "survival", NOW);
        // An expansion that is not installed comes back empty; it must not rank as zero.
        placeholders.store(broken, "%balance%", "", "survival", NOW);
        settle();

        List<Records.Ranked> ranked = placeholders.rankNumeric("%balance%", 10).join();
        assertEquals(2, ranked.size());
        assertEquals("Rich", ranked.get(0).username());
        assertEquals("Poor", ranked.get(1).username());
    }

    // ------------------------------------------------------------------ annotations

    @Test
    void highWaterCountersOnlyEverMoveUp() {
        annotations.recordHighWater("peak_online", 40, NOW, null);
        annotations.recordHighWater("peak_online", 25, NOW + 1000, null);
        settle();
        assertEquals(40L, annotations.counter("peak_online").join());

        annotations.recordHighWater("peak_online", 61, NOW + 2000, null);
        settle();
        assertEquals(61L, annotations.counter("peak_online").join());
    }

    @Test
    void alertSuppressionCountsOnlyRecentFindings() {
        UUID player = UUID.randomUUID();
        annotations.raise(NOW - 30 * DAY, "alt-detected", "warning", player, "Old", "m", null);
        settle();
        assertEquals(0, annotations.countSince("alt-detected", player, NOW - DAY).join());

        annotations.raise(NOW, "alt-detected", "warning", player, "New", "m", null);
        settle();
        assertEquals(1, annotations.countSince("alt-detected", player, NOW - DAY).join());
    }

    // ------------------------------------------------------------------ helpers

    private void login(UUID uuid, String username, long at, String address) {
        players.recordLogin(uuid, username, at, address, "GB", "United Kingdom", "London", 765,
                "1.20.1", "vanilla", "en_GB", true);
        addresses.recordConnection(uuid, address, Addresses.subnetKey(address), at);
    }

    private void loginV6(UUID uuid, String username, long at, String address) {
        players.recordLogin(uuid, username, at, address, null, null, null, 765, "1.20.1", null,
                null, true);
        addresses.recordConnection(uuid, address, Addresses.subnetKey(address), at);
    }

    private void settle() {
        writes.flush().orTimeout(10, TimeUnit.SECONDS).join();
    }
}
