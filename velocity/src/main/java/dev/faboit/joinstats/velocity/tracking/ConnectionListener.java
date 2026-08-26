package dev.faboit.joinstats.velocity.tracking;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.PlayerModInfoEvent;
import com.velocitypowered.api.event.player.PlayerSettingsChangedEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.proxy.player.SkinParts;
import com.velocitypowered.api.util.ModInfo;
import dev.faboit.joinstats.velocity.analytics.AlertService;
import dev.faboit.joinstats.velocity.bridge.PlaceholderService;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.geo.GeoData;
import dev.faboit.joinstats.velocity.geo.GeoService;
import dev.faboit.joinstats.velocity.storage.dao.AddressDao;
import dev.faboit.joinstats.velocity.storage.dao.EventDao;
import dev.faboit.joinstats.velocity.storage.dao.PlayerDao;
import dev.faboit.joinstats.velocity.util.Addresses;
import dev.faboit.joinstats.velocity.util.Json;
import dev.faboit.joinstats.velocity.util.Privacy;
import dev.faboit.joinstats.velocity.util.Versions;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Turns Velocity's events into recorded facts.
 *
 * <p>Two rules run through every handler here. Nothing blocks: an event handler enqueues work and
 * returns, because everything in this class is on a Netty thread and a single slow query would be
 * felt by every player on the proxy. And nothing is mutated: the plugin only observes, so a
 * failure in here can cost data but can never stop somebody connecting.
 */
public final class ConnectionListener {

    /**
     * How long to wait after a join before the checks that read back what the join just wrote.
     *
     * <p>Writes are queued and applied in batches, so a shared-address query run inline would be
     * looking for a row that has not been committed yet.
     */
    private static final long POST_JOIN_DELAY_SECONDS = 2;

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Supplier<PluginConfig> config;
    private final Privacy privacy;
    private final SessionManager sessions;
    private final PlayerDao players;
    private final AddressDao addresses;
    private final EventDao events;
    private final GeoService geo;
    private final AlertService alerts;
    private final PlaceholderService placeholders;

    public ConnectionListener(Object plugin, ProxyServer proxy, Supplier<PluginConfig> config,
                              Privacy privacy, SessionManager sessions, PlayerDao players,
                              AddressDao addresses, EventDao events, GeoService geo,
                              AlertService alerts, PlaceholderService placeholders, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;
        this.privacy = privacy;
        this.sessions = sessions;
        this.players = players;
        this.addresses = addresses;
        this.events = events;
        this.geo = geo;
        this.alerts = alerts;
        this.placeholders = placeholders;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ connection

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        PluginConfig settings = config.get();
        if (!settings.tracking.logins) {
            return;
        }
        String rawAddress = Addresses.ipOf(event.getConnection().getRemoteAddress());
        if (isExempt(event.getUsername(), rawAddress)) {
            return;
        }
        events.record(System.currentTimeMillis(), "connect-attempt", null, event.getUsername(),
                privacy.storedAddress(rawAddress), null, null, null,
                virtualHost(event.getConnection()),
                Json.write(Map.of("protocol",
                        Versions.protocol(event.getConnection().getProtocolVersion()))));
    }

    /**
     * Records a login another plugin has refused.
     *
     * <p>Only sees refusals decided before this handler runs, which in practice covers the
     * authentication and ban plugins that decide during pre-login.
     */
    @Subscribe
    public void onLogin(LoginEvent event) {
        if (event.getResult().isAllowed() || !config.get().tracking.logins) {
            return;
        }
        Player player = event.getPlayer();
        String rawAddress = Addresses.ipOf(player.getRemoteAddress());
        if (isExempt(player.getUsername(), rawAddress)) {
            return;
        }
        events.record(System.currentTimeMillis(), "login-denied", player.getUniqueId(),
                player.getUsername(), privacy.storedAddress(rawAddress), null, null, null,
                null, null);
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();

        String rawAddress = Addresses.ipOf(player.getRemoteAddress());
        if (isExempt(player.getUsername(), rawAddress)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        String storedAddress = privacy.storedAddress(rawAddress);
        String storedSubnet = privacy.storedSubnet(rawAddress);
        PluginConfig settings = config.get();

        int protocol = Versions.protocol(player.getProtocolVersion());
        String versionName = Versions.name(player.getProtocolVersion());
        String virtualHost = settings.tracking.virtualHosts ? virtualHost(player) : null;
        String locale = settings.tracking.clientSettings ? localeOf(player) : null;
        String brand = settings.tracking.clientDetails ? player.getClientBrand() : null;

        // The session opens first and synchronously: a player who disconnects during the
        // lookups below must still have their arrival on record.
        sessions.handleLogin(uuid, username, now, new SessionManager.Connection(
                storedAddress, storedSubnet, null, null, protocol, versionName, brand, locale,
                virtualHost, player.isOnlineMode()));

        addresses.recordConnection(uuid, storedAddress, storedSubnet, now);
        alerts.checkRapidRejoin(uuid, username, now);

        players.find(uuid).thenAccept(existing -> {
            boolean firstEver = existing.isEmpty();
            players.recordLogin(uuid, username, now, storedAddress, null, null, null, protocol,
                    versionName, brand, locale, player.isOnlineMode());
            resolveLocation(player, uuid, username, rawAddress, storedAddress, firstEver, now);
            if (!firstEver) {
                players.nameHistory(uuid)
                        .thenAccept(history -> alerts.checkNameChange(uuid, username, history));
            }
        }).exceptionally(error -> {
            logger.warn("Could not read the stored profile for {}; their join is recorded but "
                    + "first-join and name-change detection were skipped.", username, error);
            return null;
        });

        scheduleFollowUps(player, uuid, username);
    }

    /**
     * Resolves the connecting address and applies what comes back.
     *
     * <p>Runs after the session is already open, so a slow or rate-limited provider delays only
     * the country on the row, never the join itself.
     */
    private void resolveLocation(Player player, UUID uuid, String username, String rawAddress,
                                 String storedAddress, boolean firstEver, long now) {
        geo.lookup(rawAddress).thenAccept(location -> {
            if (location != null && !location.isEmpty()) {
                sessions.session(uuid).ifPresent(session ->
                        session.applyLocation(location.countryCode(), location.city()));
                players.recordLocation(uuid, location.country(), location.countryCode(),
                        location.city());
                alerts.checkVpn(uuid, username, location);
                alerts.checkImpossibleTravel(uuid, username, location,
                        sessions.sessionId(uuid), now);
            }
            if (firstEver) {
                alerts.checkFirstJoin(uuid, username, location);
            }
            maybeResolveHostname(storedAddress, rawAddress, location);
        }).exceptionally(error -> {
            logger.debug("Location lookup failed for {}.", username, error);
            return null;
        });
    }

    /** Fills in a reverse-DNS name once, for addresses that do not already have one. */
    private void maybeResolveHostname(String storedAddress, String rawAddress, GeoData location) {
        if (location != null && location.hostname() != null) {
            return;
        }
        if (Addresses.isPrivate(rawAddress)) {
            return;
        }
        geo.reverseDns(rawAddress).thenAccept(hostname -> {
            if (hostname != null) {
                addresses.updateHostname(storedAddress, hostname);
            }
        });
    }

    /** Queues the work that has to happen a moment after the join rather than during it. */
    private void scheduleFollowUps(Player player, UUID uuid, String username) {
        proxy.getScheduler().buildTask(plugin, () -> {
            if (proxy.getPlayer(uuid).isEmpty()) {
                return;
            }
            alerts.checkAlts(uuid, username);
        }).delay(POST_JOIN_DELAY_SECONDS, TimeUnit.SECONDS).schedule();

        if (placeholders.enabled()) {
            long delay = Math.max(1L, config.get().placeholders.delayAfterJoin().toMillis());
            proxy.getScheduler().buildTask(plugin, () -> {
                if (proxy.getPlayer(uuid).isPresent()) {
                    placeholders.refresh(player);
                }
            }).delay(delay, TimeUnit.MILLISECONDS).schedule();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        String rawAddress = Addresses.ipOf(player.getRemoteAddress());
        if (isExempt(player.getUsername(), rawAddress)) {
            return;
        }
        if (!config.get().tracking.disconnects) {
            return;
        }
        placeholders.captureOnQuit(player);
        sessions.handleDisconnect(player.getUniqueId(), now,
                event.getLoginStatus() == null ? null : event.getLoginStatus().name());
    }

    // ------------------------------------------------------------------ routing

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        PluginConfig settings = config.get();
        if (!settings.tracking.serverSwitches) {
            return;
        }
        Player player = event.getPlayer();
        if (isExempt(player.getUsername(), Addresses.ipOf(player.getRemoteAddress()))) {
            return;
        }
        String server = event.getServer().getServerInfo().getName();
        if (PluginConfig.containsIgnoreCase(settings.tracking.ignoredServers, server)) {
            return;
        }
        String previous = event.getPreviousServer()
                .map(registered -> registered.getServerInfo().getName()).orElse(null);
        sessions.handleServerConnected(player.getUniqueId(), server, previous,
                System.currentTimeMillis());
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        PluginConfig settings = config.get();
        if (!settings.tracking.kicks) {
            return;
        }
        Player player = event.getPlayer();
        if (isExempt(player.getUsername(), Addresses.ipOf(player.getRemoteAddress()))) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String reason = event.getServerKickReason()
                .map(component -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(component))
                .orElse("no reason given");

        sessions.session(uuid).ifPresent(LiveSession::countKick);
        players.increment(uuid, PlayerDao.Counter.KICKS, 1);
        events.record(System.currentTimeMillis(), "kick", uuid, player.getUsername(),
                privacy.storedAddress(Addresses.ipOf(player.getRemoteAddress())),
                event.getServer().getServerInfo().getName(), null, sessions.sessionId(uuid),
                reason, Json.write(Map.of(
                        "duringConnect", event.kickedDuringServerConnect())));
    }

    // ------------------------------------------------------------------ activity

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        PluginConfig settings = config.get();
        if (!settings.tracking.chat) {
            return;
        }
        Player player = event.getPlayer();
        if (isExempt(player.getUsername(), Addresses.ipOf(player.getRemoteAddress()))) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String message = event.getMessage();
        String server = currentServer(player);

        sessions.session(uuid).ifPresent(session -> {
            session.countChat();
            session.touch(System.currentTimeMillis(), settings.sessions.idle().toMillis());
        });
        players.increment(uuid, PlayerDao.Counter.CHAT_MESSAGES, 1);
        events.recordChat(System.currentTimeMillis(), uuid, player.getUsername(), server,
                privacy.storeChatContent() ? message : null, message.length(),
                !event.getResult().isAllowed());
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        PluginConfig settings = config.get();
        if (!settings.tracking.commands) {
            return;
        }
        CommandSource source = event.getCommandSource();
        if (!(source instanceof Player player)) {
            return;
        }
        if (isExempt(player.getUsername(), Addresses.ipOf(player.getRemoteAddress()))) {
            return;
        }

        String raw = event.getCommand();
        String name = PluginConfig.commandName(raw);
        int space = raw.indexOf(' ');
        String arguments = space < 0 ? null : raw.substring(space + 1);
        UUID uuid = player.getUniqueId();

        sessions.session(uuid).ifPresent(session -> {
            session.countCommand();
            session.touch(System.currentTimeMillis(), settings.sessions.idle().toMillis());
        });
        players.increment(uuid, PlayerDao.Counter.COMMANDS, 1);
        events.recordCommand(System.currentTimeMillis(), uuid, player.getUsername(),
                currentServer(player), name,
                privacy.storeCommandArguments(name) ? arguments : null,
                !event.getResult().isAllowed());
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        if (!config.get().tracking.pings) {
            return;
        }
        InboundConnection connection = event.getConnection();
        String rawAddress = Addresses.ipOf(connection.getRemoteAddress());
        if (Addresses.matchesAny(rawAddress, config.get().tracking.exemptAddresses)) {
            return;
        }
        events.recordPing(System.currentTimeMillis(), privacy.storedAddress(rawAddress),
                virtualHost(connection), Versions.protocol(connection.getProtocolVersion()),
                Versions.name(connection.getProtocolVersion()));
    }

    // ------------------------------------------------------------------ client details

    @Subscribe
    public void onSettingsChanged(PlayerSettingsChangedEvent event) {
        if (!config.get().tracking.clientSettings) {
            return;
        }
        Player player = event.getPlayer();
        PlayerSettings settings = event.getPlayerSettings();
        String locale = settings.getLocale() == null ? null : settings.getLocale().toLanguageTag();

        sessions.session(player.getUniqueId()).ifPresent(session -> session.applySettings(
                locale,
                settings.getViewDistance(),
                settings.getChatMode() == null ? null
                        : settings.getChatMode().name().toLowerCase(Locale.ROOT),
                skinPartsBitmask(settings.getSkinParts()),
                settings.getMainHand() == null ? null
                        : settings.getMainHand().name().toLowerCase(Locale.ROOT)));
        players.recordClientDetails(player.getUniqueId(), null, locale);
    }

    @Subscribe
    public void onClientBrand(PlayerClientBrandEvent event) {
        if (!config.get().tracking.clientDetails) {
            return;
        }
        Player player = event.getPlayer();
        String brand = event.getBrand();
        sessions.session(player.getUniqueId()).ifPresent(session -> session.applyBrand(brand));
        players.recordClientDetails(player.getUniqueId(), brand, null);
    }

    @Subscribe
    public void onModInfo(PlayerModInfoEvent event) {
        if (!config.get().tracking.clientDetails) {
            return;
        }
        ModInfo info = event.getModInfo();
        if (info == null || info.getMods().isEmpty()) {
            return;
        }
        Map<String, String> mods = new LinkedHashMap<>();
        for (ModInfo.Mod mod : info.getMods()) {
            mods.put(mod.getId(), mod.getVersion());
        }
        String encoded = Json.write(mods);
        Player player = event.getPlayer();
        sessions.session(player.getUniqueId()).ifPresent(session -> session.applyMods(encoded));
        events.record(System.currentTimeMillis(), "mods", player.getUniqueId(),
                player.getUsername(), null, currentServer(player), null,
                sessions.sessionId(player.getUniqueId()),
                info.getType() + " (" + mods.size() + " mods)", encoded);
    }

    // ------------------------------------------------------------------ periodic

    /** Samples every online player's latency. Driven by the scheduler. */
    public void samplePings() {
        for (Player player : proxy.getAllPlayers()) {
            long ping = player.getPing();
            // Velocity reports -1 before the first keep-alive round trip completes.
            if (ping < 0) {
                continue;
            }
            UUID uuid = player.getUniqueId();
            sessions.session(uuid).ifPresent(session -> session.recordPing(ping));
            players.recordPing(uuid, (int) Math.min(Integer.MAX_VALUE, ping));
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean isExempt(String username, String rawAddress) {
        PluginConfig.Tracking tracking = config.get().tracking;
        return PluginConfig.containsIgnoreCase(tracking.exemptPlayers, username)
                || Addresses.matchesAny(rawAddress, tracking.exemptAddresses);
    }

    private static String currentServer(Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
    }

    private static String virtualHost(InboundConnection connection) {
        Optional<String> raw = connection.getRawVirtualHost();
        if (raw.isPresent() && !raw.get().isBlank()) {
            return raw.get();
        }
        return connection.getVirtualHost().map(InetSocketAddress::getHostString).orElse(null);
    }

    private static String localeOf(Player player) {
        if (!player.hasSentPlayerSettings()) {
            return null;
        }
        PlayerSettings settings = player.getPlayerSettings();
        return settings.getLocale() == null ? null : settings.getLocale().toLanguageTag();
    }

    /**
     * Rebuilds the skin-parts bitmask the client sent.
     *
     * <p>Velocity exposes the flags individually rather than the byte, so the bits are put back
     * in the order the protocol defines. Stored as one integer because the interesting question
     * is almost always "which parts are hidden", not each flag on its own.
     */
    private static int skinPartsBitmask(SkinParts parts) {
        if (parts == null) {
            return 0;
        }
        int mask = 0;
        if (parts.hasCape()) {
            mask |= 1;
        }
        if (parts.hasJacket()) {
            mask |= 1 << 1;
        }
        if (parts.hasLeftSleeve()) {
            mask |= 1 << 2;
        }
        if (parts.hasRightSleeve()) {
            mask |= 1 << 3;
        }
        if (parts.hasLeftPants()) {
            mask |= 1 << 4;
        }
        if (parts.hasRightPants()) {
            mask |= 1 << 5;
        }
        if (parts.hasHat()) {
            mask |= 1 << 6;
        }
        return mask;
    }

    /** The names of every backend the proxy knows, for command completion. */
    public List<String> knownServers() {
        List<String> names = new ArrayList<>();
        proxy.getAllServers().forEach(server -> names.add(server.getServerInfo().getName()));
        return names;
    }
}
