package dev.faboit.joinstats.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurateException;

class ConfigManagerTest {

    @TempDir
    Path directory;

    private ConfigManager manager() {
        return new ConfigManager(directory, LoggerFactory.getLogger(ConfigManagerTest.class));
    }

    @Test
    void writesBothFilesOnFirstRunWithTheirDocumentation() throws Exception {
        ConfigManager config = manager();
        config.load();

        Path main = directory.resolve("config.conf");
        Path messages = directory.resolve("messages.conf");
        assertTrue(Files.exists(main));
        assertTrue(Files.exists(messages));

        String written = Files.readString(main);
        assertTrue(written.contains("rejoin-grace"),
                "the generated file should carry every option");
        assertTrue(written.contains("#"), "and the comments explaining them");
        assertTrue(written.contains("continues their previous session"),
                "the grace window's explanation should survive the round trip");
    }

    @Test
    void generatesAnAddressSaltAndKeepsItAcrossRestarts() throws Exception {
        ConfigManager first = manager();
        first.load();
        String salt = first.config().privacy.addressSalt;

        assertFalse(salt.isBlank(), "a salt must be generated on first run");
        assertTrue(salt.length() >= 32);

        // A salt that changed every boot would make every stored address unmatchable and
        // silently break alt detection.
        ConfigManager second = manager();
        second.load();
        assertEquals(salt, second.config().privacy.addressSalt);
    }

    @Test
    void differentInstallsGetDifferentSalts() throws Exception {
        ConfigManager first = manager();
        first.load();

        Path other = directory.resolve("other");
        ConfigManager second = new ConfigManager(other,
                LoggerFactory.getLogger(ConfigManagerTest.class));
        second.load();

        assertNotEquals(first.config().privacy.addressSalt, second.config().privacy.addressSalt);
    }

    @Test
    void readsBackEditedValues() throws Exception {
        ConfigManager config = manager();
        config.load();

        Path main = directory.resolve("config.conf");
        String edited = Files.readString(main)
                .replace("rejoin-grace=\"30s\"", "rejoin-grace=\"90s\"")
                .replace("rejoin-grace=30s", "rejoin-grace=90s");
        Files.writeString(main, edited);

        ConfigManager reloaded = manager();
        reloaded.load();
        assertEquals(Duration.ofSeconds(90), reloaded.config().sessions.grace());
    }

    @Test
    void fillsInOptionsMissingFromAnOlderFile() throws Exception {
        // An install upgrading from a release that predates half these settings.
        Files.writeString(directory.resolve("config.conf"), """
                storage {
                    file="statistics.db"
                }
                sessions {
                    rejoin-grace="45s"
                }
                """);

        ConfigManager config = manager();
        config.load();

        assertEquals(Duration.ofSeconds(45), config.config().sessions.grace(),
                "the operator's own value must be preserved");
        assertEquals(250, config.config().storage.batchSize,
                "and options they have never seen must appear at their default");
        assertTrue(Files.readString(directory.resolve("config.conf")).contains("batch-size"),
                "the new options should be written back so they are discoverable");
    }

    @Test
    void refusesToStartOnAMalformedFileRatherThanSilentlyResetting() throws Exception {
        Files.writeString(directory.resolve("config.conf"), "storage { file = \"a.db\" ");
        ConfigManager config = manager();
        // Quietly reverting a tuned config to defaults would be far worse than refusing.
        assertThrows(ConfigurateException.class, config::load);
    }

    @Test
    void durationAccessorsFallBackWhenAValueIsNonsense() {
        PluginConfig config = new PluginConfig();
        config.sessions.rejoinGrace = "half an hour";
        assertEquals(Duration.ofSeconds(30), config.sessions.grace(),
                "a typo should degrade to the documented default");

        config.population.interval = "";
        assertEquals(Duration.ofSeconds(1), config.population.sampleInterval());
    }

    @Test
    void timezoneFallsBackToTheHostForAnUnknownZone() {
        PluginConfig config = new PluginConfig();
        config.general.timezone = "Middle/Earth";
        assertEquals(ZoneId.systemDefault(), config.general.zone());

        config.general.timezone = "Europe/Berlin";
        assertEquals(ZoneId.of("Europe/Berlin"), config.general.zone());

        config.general.timezone = "system";
        assertEquals(ZoneId.systemDefault(), config.general.zone());
    }

    @Test
    void rollupBucketsDropUnparseableEntries() {
        PluginConfig config = new PluginConfig();
        config.population.rollups = new java.util.ArrayList<>(
                java.util.List.of("1m", "nonsense", "1h"));
        assertEquals(java.util.List.of(Duration.ofMinutes(1), Duration.ofHours(1)),
                config.population.rollupBuckets());
    }

    @Test
    void sensitiveCommandsAreRecognisedRegardlessOfPrefixOrCase() {
        assertEquals("login", PluginConfig.commandName("/LOGIN hunter2"));
        assertEquals("login", PluginConfig.commandName("login hunter2"));
        assertEquals("login", PluginConfig.commandName("/authme:login hunter2"),
                "a namespaced alias must still resolve to the command name");

        PluginConfig config = new PluginConfig();
        assertTrue(PluginConfig.containsIgnoreCase(config.tracking.sensitiveCommands, "LOGIN"));
        assertFalse(PluginConfig.containsIgnoreCase(config.tracking.sensitiveCommands, "spawn"));
    }

    @Test
    void webhookEventFilteringHonoursTheWildcard() {
        PluginConfig.Webhooks webhooks = new PluginConfig.Webhooks();
        assertTrue(webhooks.sends("first-join"));
        assertFalse(webhooks.sends("server-switch"));

        webhooks.events = new java.util.ArrayList<>(java.util.List.of("*"));
        assertTrue(webhooks.sends("anything-at-all"));
    }
}
