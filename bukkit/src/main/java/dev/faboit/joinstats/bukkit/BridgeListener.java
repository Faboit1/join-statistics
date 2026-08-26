package dev.faboit.joinstats.bukkit;

import dev.faboit.joinstats.protocol.BridgeCodec;
import dev.faboit.joinstats.protocol.BridgeProtocol;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Decodes the proxy's requests and sends the answers back. */
final class BridgeListener implements PluginMessageListener {

    private final JoinStatsCompanion plugin;
    private final PlaceholderResolver resolver;
    private boolean warnedAboutRevision;

    BridgeListener(JoinStatsCompanion plugin, PlaceholderResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] data) {
        if (!BridgeProtocol.CHANNEL.equals(channel)) {
            return;
        }
        try {
            BridgeCodec.Frame frame = BridgeCodec.decode(data);
            if (frame == null) {
                if (!warnedAboutRevision) {
                    warnedAboutRevision = true;
                    plugin.getLogger().warning("The proxy speaks a different bridge revision. "
                            + "Update this companion and the proxy plugin to the same version.");
                }
                return;
            }
            switch (frame.opcode()) {
                case BridgeProtocol.REQUEST_PLACEHOLDERS ->
                        handlePlaceholderRequest(carrier, frame.body());
                case BridgeProtocol.REQUEST_SERVER_INFO ->
                        handleServerInfoRequest(carrier, frame.body());
                case BridgeProtocol.WELCOME -> {
                    // The proxy acknowledging our handshake. Nothing to do.
                }
                default -> plugin.getLogger().fine(
                        "Ignoring unknown bridge opcode " + frame.opcode());
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Malformed bridge message from the proxy.", e);
        }
    }

    private void handlePlaceholderRequest(Player carrier, DataInputStream body) throws IOException {
        long requestId = body.readLong();
        UUID target = BridgeCodec.readUuid(body);
        int count = BridgeCodec.readCount(body);

        List<String> placeholders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            placeholders.add(BridgeCodec.readString(body));
        }

        // The proxy addresses a request to a player but routes it down whichever connection is
        // convenient, so resolve against the account it actually asked about.
        Player subject = carrier.getUniqueId().equals(target)
                ? carrier : plugin.getServer().getPlayer(target);
        if (subject == null) {
            // They left between the request being sent and it arriving. Reply anyway, so the
            // proxy can retire the request now rather than waiting out its timeout.
            send(carrier, BridgeCodec.frame(BridgeProtocol.RESPONSE_PLACEHOLDERS, out -> {
                out.writeLong(requestId);
                BridgeCodec.writeUuid(out, target);
                out.writeInt(0);
            }));
            return;
        }

        Map<String, String> values = resolver.resolve(subject, placeholders);
        send(carrier, BridgeCodec.frame(BridgeProtocol.RESPONSE_PLACEHOLDERS, out -> {
            out.writeLong(requestId);
            BridgeCodec.writeUuid(out, target);
            out.writeInt(values.size());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                BridgeCodec.writeString(out, entry.getKey());
                BridgeCodec.writeString(out, entry.getValue() == null ? "" : entry.getValue());
            }
        }));
    }

    private void handleServerInfoRequest(Player carrier, DataInputStream body) throws IOException {
        long requestId = body.readLong();
        String info = ServerInfo.describe(plugin);
        send(carrier, BridgeCodec.frame(BridgeProtocol.RESPONSE_SERVER_INFO, out -> {
            out.writeLong(requestId);
            BridgeCodec.writeString(out, info);
        }));
    }

    /** Introduces this server, so the proxy knows the companion is here and what it can do. */
    void sayHello(Player carrier) {
        send(carrier, BridgeCodec.frame(BridgeProtocol.HELLO, out -> {
            BridgeCodec.writeString(out, plugin.companionVersion());
            BridgeCodec.writeString(out, plugin.getServer().getName());
            out.writeBoolean(resolver.available());
            BridgeCodec.writeString(out, resolver.placeholderApiVersion());
        }));
    }

    private void send(Player carrier, byte[] payload) {
        if (carrier == null || !carrier.isOnline()) {
            return;
        }
        try {
            carrier.sendPluginMessage(plugin, BridgeProtocol.CHANNEL, payload);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Could not reply to the proxy.", e);
        }
    }
}
