package dev.faboit.joinstats.velocity.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Loads {@code config.conf} and {@code messages.conf}, and hands out the current snapshot.
 *
 * <p>Both files are written back after loading. That round-trip is what makes an upgrade
 * painless: options added in a new release appear in the operator's existing file, with their
 * documentation, at their default value.
 *
 * <p>A reload swaps a single {@link AtomicReference}, so a request already in flight keeps the
 * configuration it started with rather than seeing half of each.
 */
public final class ConfigManager {

    private final Path dataDirectory;
    private final Logger logger;
    private final AtomicReference<PluginConfig> config = new AtomicReference<>(new PluginConfig());
    private final AtomicReference<Messages> messages = new AtomicReference<>(new Messages());

    public ConfigManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public PluginConfig config() {
        return config.get();
    }

    public Messages messages() {
        return messages.get();
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    /**
     * Reads both files from disk.
     *
     * @throws ConfigurateException if a file exists but cannot be parsed — the caller keeps the
     *                              previous snapshot rather than starting with defaults, because
     *                              silently reverting a tuned config to stock would be worse than
     *                              refusing the reload.
     */
    public void load() throws ConfigurateException, IOException {
        Files.createDirectories(dataDirectory);

        PluginConfig loadedConfig = loadFile(dataDirectory.resolve("config.conf"), PluginConfig.class,
                new PluginConfig(), Header.CONFIG);
        ensureAddressSalt(loadedConfig);

        Messages loadedMessages = loadFile(dataDirectory.resolve("messages.conf"), Messages.class,
                new Messages(), Header.MESSAGES);

        config.set(loadedConfig);
        messages.set(loadedMessages);
    }

    private <T> T loadFile(Path path, Class<T> type, T fallback, String header)
            throws ConfigurateException {
        HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                .path(path)
                .defaultOptions(options -> options.shouldCopyDefaults(true).header(header))
                .build();

        CommentedConfigurationNode root = loader.load();
        T value = root.get(type);
        if (value == null) {
            value = fallback;
        }
        // Writing the node back is what materialises defaults and comments for keys the
        // operator's file does not have yet.
        root.set(type, value);
        loader.save(root);
        return value;
    }

    /**
     * Generates the address salt on first run and persists it.
     *
     * <p>Done here rather than lazily at hash time so the value survives a restart — a salt that
     * changed every boot would make every stored address a fresh, unmatchable identifier and
     * quietly break alt detection.
     */
    private void ensureAddressSalt(PluginConfig loaded) {
        if (!loaded.privacy.addressSalt.isBlank()) {
            return;
        }
        byte[] entropy = new byte[32];
        new SecureRandom().nextBytes(entropy);
        loaded.privacy.addressSalt = HexFormat.of().formatHex(entropy);

        try {
            Path path = dataDirectory.resolve("config.conf");
            HoconConfigurationLoader loader = HoconConfigurationLoader.builder().path(path).build();
            CommentedConfigurationNode root = loader.load();
            root.node("privacy", "address-salt").set(loaded.privacy.addressSalt);
            loader.save(root);
            logger.info("Generated a new address salt and stored it in config.conf.");
        } catch (ConfigurateException e) {
            logger.warn("Could not persist the generated address salt; hashing will be "
                    + "inconsistent across restarts until config.conf is writable.", e);
        }
    }

    private static final class Header {
        static final String CONFIG = """
                JoinStatistics — proxy-side player analytics for Velocity.

                Durations accept a unit suffix: 30s, 5m, 2h, 7d, 1w. A bare number means seconds.
                A value this plugin cannot parse falls back to the documented default and logs a
                warning rather than preventing startup.

                Full documentation: https://github.com/faboit1/join-statistics
                """;

        static final String MESSAGES = """
                Player-facing text, in MiniMessage format: https://docs.advntr.dev/minimessage/

                Available everywhere: <prefix>, and the style tags <accent> <accent2> <label>
                <value> <good> <warn> <bad>, which take their colours from the top of this file.
                Each message also has its own placeholders, noted alongside it.
                """;
    }
}
