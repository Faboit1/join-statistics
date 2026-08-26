package dev.faboit.joinstats.bukkit;

import dev.faboit.joinstats.protocol.BridgeProtocol;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The backend half of JoinStatistics.
 *
 * <p>Velocity cannot see a backend's PlaceholderAPI: expansions run inside this server and read
 * state that never crosses the proxy boundary. This plugin answers the proxy's requests for those
 * values over a plugin message channel, and does nothing else — it stores nothing, registers no
 * commands, and adds no listeners beyond the one needed to introduce itself.
 *
 * <p>It is optional. Without it, every other part of JoinStatistics still works; the placeholder
 * tables simply stay empty.
 */
public final class JoinStatsCompanion extends JavaPlugin implements Listener {

    private BridgeListener bridge;
    private PlaceholderResolver resolver;

    @Override
    public void onEnable() {
        boolean placeholderApi = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        resolver = new PlaceholderResolver(this, placeholderApi);
        bridge = new BridgeListener(this, resolver);

        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeProtocol.CHANNEL);
        getServer().getMessenger()
                .registerIncomingPluginChannel(this, BridgeProtocol.CHANNEL, bridge);
        getServer().getPluginManager().registerEvents(this, this);

        if (placeholderApi) {
            getLogger().info("PlaceholderAPI found; the proxy can resolve placeholders here.");
        } else {
            getLogger().warning("PlaceholderAPI is not installed. The proxy will still see this "
                    + "server, but every placeholder it asks for will come back empty.");
        }

        // A reload with players already on has no join event to trigger the handshake.
        for (Player player : getServer().getOnlinePlayers()) {
            bridge.sayHello(player);
            break;
        }
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    /**
     * Announces this server to the proxy on the first join.
     *
     * <p>A plugin message needs a player connection to travel on, so the handshake cannot happen
     * at startup. Sent for every join rather than only the first: the proxy may have restarted
     * since, and repeating a handshake it already has costs one packet.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) {
                bridge.sayHello(player);
            }
        }, 20L);
    }

    /** The version string reported in the handshake. */
    // See the note in PlaceholderResolver: getDescription() is the accessor available across
    // every server version this companion is meant to run on.
    @SuppressWarnings("deprecation")
    public String companionVersion() {
        return getDescription().getVersion();
    }
}
