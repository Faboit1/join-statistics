package dev.faboit.joinstats.bukkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Resolves placeholder strings through PlaceholderAPI, if it is installed.
 *
 * <p>Every resolution happens on the main server thread, because expansions read live world and
 * entity state and are not written to be called from anywhere else. That makes the cost of a
 * request the operator's to manage, so a slow batch is reported rather than hidden.
 */
final class PlaceholderResolver {

    /** Log a warning when one batch takes longer than this — it is a tick the server lost. */
    private static final long SLOW_BATCH_MILLIS = 50;

    private final Plugin plugin;
    private final boolean available;
    private boolean warnedAboutSlowness;

    PlaceholderResolver(Plugin plugin, boolean available) {
        this.plugin = plugin;
        this.available = available;
    }

    boolean available() {
        return available;
    }

    // getPluginMeta() supersedes getDescription() on modern Paper, but only there and only in
    // API versions newer than this companion targets on purpose.
    @SuppressWarnings("deprecation")
    String placeholderApiVersion() {
        if (!available) {
            return "";
        }
        Plugin papi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        return papi == null ? "" : papi.getDescription().getVersion();
    }

    /**
     * Resolves a batch for one player.
     *
     * <p>A placeholder no expansion can handle comes back as the literal string that was asked
     * for; that is reported as an empty value rather than echoed, because "PlaceholderAPI does not
     * know this one" is a genuinely useful thing for the proxy to record, and echoing the input
     * would make it indistinguishable from a placeholder that legitimately resolves to itself.
     */
    Map<String, String> resolve(Player player, List<String> placeholders) {
        Map<String, String> values = new LinkedHashMap<>(Math.max(4, placeholders.size()));
        if (!available) {
            for (String placeholder : placeholders) {
                values.put(placeholder, "");
            }
            return values;
        }

        long started = System.nanoTime();
        for (String placeholder : placeholders) {
            String resolved;
            try {
                resolved = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player,
                        placeholder);
            } catch (Throwable error) {
                // One misbehaving expansion must not cost the whole batch.
                plugin.getLogger().log(Level.WARNING,
                        "An expansion threw while resolving " + placeholder, error);
                resolved = "";
            }
            values.put(placeholder, placeholder.equals(resolved) ? "" : resolved);
        }

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        if (elapsedMillis > SLOW_BATCH_MILLIS && !warnedAboutSlowness) {
            warnedAboutSlowness = true;
            plugin.getLogger().warning(String.format(
                    "Resolving %d placeholders took %dms on the main thread. Reduce "
                            + "placeholders.track or raise placeholders.refresh-interval on the "
                            + "proxy. This warning is only shown once.",
                    placeholders.size(), elapsedMillis));
        }
        return values;
    }
}
