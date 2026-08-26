package dev.faboit.joinstats.bukkit;

import java.util.StringJoiner;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Describes this server for the proxy's {@code REQUEST_SERVER_INFO}.
 *
 * <p>Hand-built JSON rather than a library: the companion is meant to be a jar you drop on a
 * backend without thinking about it, and shipping a JSON dependency for one object of nine fields
 * would be the largest thing in it.
 */
final class ServerInfo {

    private ServerInfo() {
    }

    static String describe(Plugin plugin) {
        var server = plugin.getServer();
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        field(json, "name", server.getName()).append(',');
        field(json, "version", server.getVersion()).append(',');
        field(json, "bukkitVersion", server.getBukkitVersion()).append(',');
        json.append("\"online\":").append(server.getOnlinePlayers().size()).append(',');
        json.append("\"maxPlayers\":").append(server.getMaxPlayers()).append(',');
        json.append("\"plugins\":").append(server.getPluginManager().getPlugins().length)
                .append(',');
        json.append("\"tps\":").append(readTps(server)).append(',');

        StringJoiner worlds = new StringJoiner(",", "[", "]");
        for (World world : server.getWorlds()) {
            worlds.add("{\"name\":" + quote(world.getName())
                    + ",\"entities\":" + world.getEntities().size()
                    + ",\"chunks\":" + world.getLoadedChunks().length
                    + ",\"players\":" + world.getPlayers().size() + "}");
        }
        json.append("\"worlds\":").append(worlds);
        return json.append('}').toString();
    }

    /**
     * Reads the server's tick rate.
     *
     * <p>{@code getTPS} is a Paper extension. Catching {@link Throwable} rather than a specific
     * exception is deliberate: on a plain Spigot server the call fails with a
     * {@link NoSuchMethodError}, which is an Error and not an Exception.
     */
    private static double readTps(org.bukkit.Server server) {
        try {
            double[] rates = server.getTPS();
            return rates.length == 0 ? -1 : Math.round(rates[0] * 100.0) / 100.0;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static StringBuilder field(StringBuilder json, String key, String value) {
        return json.append('"').append(key).append("\":").append(quote(value));
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
