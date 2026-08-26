package dev.faboit.joinstats.velocity.bridge;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.faboit.joinstats.protocol.BridgeCodec;
import dev.faboit.joinstats.protocol.BridgeProtocol;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * The proxy end of the plugin-message channel the backend companion speaks.
 *
 * <p>Velocity has no view of a backend's PlaceholderAPI: expansions run inside the Bukkit server
 * and read state (a balance, a rank, a world) that never crosses the proxy boundary. So the proxy
 * asks, and the companion answers, over one channel carried on the player's own connection.
 *
 * <p>Requests are correlated by an id and time out on their own. A backend without the companion
 * installed never replies at all, which is a normal state rather than an error — the timeout is
 * what keeps those requests from accumulating.
 */
public final class BridgeService {

    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Companion> companions = new ConcurrentHashMap<>();
    private final AtomicLong answered = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();

    public BridgeService(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    /** Registers the channel so Velocity delivers its messages instead of dropping them. */
    public void register() {
        proxy.getChannelRegistrar().register(CHANNEL);
    }

    public void unregister() {
        proxy.getChannelRegistrar().unregister(CHANNEL);
        for (Pending request : pending.values()) {
            request.future.complete(Map.of());
        }
        pending.clear();
        companions.clear();
    }

    /** Backends that have announced a companion, keyed by server name. */
    public Map<String, Companion> companions() {
        return Map.copyOf(companions);
    }

    public boolean hasCompanion(String server) {
        return server != null && companions.containsKey(server);
    }

    public Stats stats() {
        return new Stats(companions.size(), pending.size(), answered.get(), timedOut.get());
    }

    /**
     * Asks the player's current backend to resolve a batch of placeholders for them.
     *
     * <p>Completes with an empty map rather than failing when there is no backend, no companion,
     * or no reply: every caller's response to a failure is the same, and an exceptional future
     * here would only mean the same recovery written out at each call site.
     */
    public CompletableFuture<Map<String, String>> requestPlaceholders(Player player,
                                                                      List<String> placeholders,
                                                                      long timeoutMillis) {
        ServerConnection connection = player.getCurrentServer().orElse(null);
        if (connection == null || placeholders.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        long requestId = requestIds.incrementAndGet();
        CompletableFuture<Map<String, String>> future = new CompletableFuture<>();
        pending.put(requestId, new Pending(future, System.currentTimeMillis() + timeoutMillis));

        byte[] frame = BridgeCodec.frame(BridgeProtocol.REQUEST_PLACEHOLDERS, out -> {
            out.writeLong(requestId);
            BridgeCodec.writeUuid(out, player.getUniqueId());
            out.writeInt(placeholders.size());
            for (String placeholder : placeholders) {
                BridgeCodec.writeString(out, placeholder);
            }
        });

        if (!send(connection, frame)) {
            pending.remove(requestId);
            return CompletableFuture.completedFuture(Map.of());
        }
        return future;
    }

    /**
     * Sends a frame down a backend connection, treating a dead one as a failed send.
     *
     * <p>{@code sendPluginMessage} does not merely return false once the channel is gone — it
     * throws {@link IllegalStateException}. A player quitting is exactly when that happens, and
     * letting it escape put an optional feature in a position to break whatever the caller was
     * doing.
     */
    private boolean send(ServerConnection connection, byte[] frame) {
        try {
            return connection.sendPluginMessage(CHANNEL, frame);
        } catch (RuntimeException e) {
            logger.debug("Backend connection for {} was gone before the frame could be sent.",
                    connection.getServerInfo().getName(), e);
            return false;
        }
    }

    /** Asks a backend to describe itself. Requires at least one player on that server. */
    public CompletableFuture<String> requestServerInfo(Player player, long timeoutMillis) {
        ServerConnection connection = player.getCurrentServer().orElse(null);
        if (connection == null) {
            return CompletableFuture.completedFuture(null);
        }
        long requestId = requestIds.incrementAndGet();
        CompletableFuture<Map<String, String>> future = new CompletableFuture<>();
        pending.put(requestId, new Pending(future, System.currentTimeMillis() + timeoutMillis));

        byte[] frame = BridgeCodec.frame(BridgeProtocol.REQUEST_SERVER_INFO,
                out -> out.writeLong(requestId));
        if (!send(connection, frame)) {
            pending.remove(requestId);
            return CompletableFuture.completedFuture(null);
        }
        return future.thenApply(values -> values.get("info"));
    }

    /** Fails requests whose deadline has passed. Driven by the scheduler. */
    public void expireStaleRequests(long now) {
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().deadline > now) {
                return false;
            }
            timedOut.incrementAndGet();
            entry.getValue().future.complete(Map.of());
            return true;
        });
    }

    // ------------------------------------------------------------------ inbound

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.getId().equals(event.getIdentifier().getId())) {
            return;
        }
        // Ours to consume: never relay it on to the client.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection source)) {
            // A client claiming to speak our protocol is either a curious mod or an attempt to
            // inject values; either way the proxy is not interested.
            return;
        }

        try {
            BridgeCodec.Frame frame = BridgeCodec.decode(event.getData());
            if (frame == null) {
                logger.warn("Backend '{}' speaks an incompatible bridge revision; update the "
                                + "JoinStatistics companion there to match the proxy plugin.",
                        source.getServerInfo().getName());
                return;
            }
            switch (frame.opcode()) {
                case BridgeProtocol.HELLO -> handleHello(source, frame.body());
                case BridgeProtocol.RESPONSE_PLACEHOLDERS -> handlePlaceholders(frame.body());
                case BridgeProtocol.RESPONSE_SERVER_INFO -> handleServerInfo(frame.body());
                default -> logger.debug("Ignoring unknown bridge opcode {} from {}.",
                        frame.opcode(), source.getServerInfo().getName());
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Malformed bridge message from backend '{}'.",
                    source.getServerInfo().getName(), e);
        }
    }

    private void handleHello(ServerConnection source, DataInputStream body) throws IOException {
        String companionVersion = BridgeCodec.readString(body);
        String serverName = BridgeCodec.readString(body);
        boolean papiPresent = body.readBoolean();
        String papiVersion = BridgeCodec.readString(body);

        String key = source.getServerInfo().getName();
        Companion previous = companions.put(key,
                new Companion(companionVersion, serverName, papiPresent, papiVersion,
                        System.currentTimeMillis()));
        if (previous == null) {
            if (papiPresent) {
                logger.info("Backend '{}' is running the JoinStatistics companion {} with "
                        + "PlaceholderAPI {}.", key, companionVersion, papiVersion);
            } else {
                logger.warn("Backend '{}' is running the JoinStatistics companion {} but has no "
                        + "PlaceholderAPI; placeholders from that server will be empty.",
                        key, companionVersion);
            }
        }

        // Same hazard as above: the carrying connection may already be gone by the time we
        // answer a handshake, and a failed courtesy reply must not become an exception.
        send(source, BridgeCodec.frame(BridgeProtocol.WELCOME,
                out -> BridgeCodec.writeString(out,
                        dev.faboit.joinstats.velocity.BuildConstants.VERSION)));
    }

    private void handlePlaceholders(DataInputStream body) throws IOException {
        long requestId = body.readLong();
        UUID uuid = BridgeCodec.readUuid(body);
        int count = BridgeCodec.readCount(body);

        Map<String, String> values = new LinkedHashMap<>(Math.max(4, count));
        for (int i = 0; i < count; i++) {
            String key = BridgeCodec.readString(body);
            values.put(key, BridgeCodec.readString(body));
        }

        Pending request = pending.remove(requestId);
        if (request == null) {
            // The request already timed out; the reply is late but harmless.
            logger.debug("Dropping a late placeholder reply for {} ({} values).", uuid, count);
            return;
        }
        answered.incrementAndGet();
        request.future.complete(values);
    }

    private void handleServerInfo(DataInputStream body) throws IOException {
        long requestId = body.readLong();
        String info = BridgeCodec.readString(body);
        Pending request = pending.remove(requestId);
        if (request != null) {
            answered.incrementAndGet();
            request.future.complete(Map.of("info", info));
        }
    }

    private record Pending(CompletableFuture<Map<String, String>> future, long deadline) {
    }

    /** What a backend told us about itself when it said hello. */
    public record Companion(String version, String serverName, boolean placeholderApi,
                            String placeholderApiVersion, long lastSeen) {
    }

    /** Bridge counters, for {@code /joinstats status}. */
    public record Stats(int backends, int pending, long answered, long timedOut) {
    }
}
