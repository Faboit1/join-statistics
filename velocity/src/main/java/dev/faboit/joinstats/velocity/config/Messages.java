package dev.faboit.joinstats.velocity.config;

import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * Every player-visible string, in MiniMessage format.
 *
 * <p>Kept in its own {@code messages.conf} so that translating the plugin never means merging
 * someone else's changes to the operational settings.
 */
@ConfigSerializable
public class Messages {

    @Comment("Prefixed to every message that uses <prefix>.")
    public String prefix = "<gradient:#7aa2f7:#bb9af7><bold>JoinStats</bold></gradient> <dark_gray>»</dark_gray> ";

    @Comment("Accent colours reused across the output, so a re-theme is a two-line change.")
    public String accent = "#7aa2f7";
    public String accentAlt = "#bb9af7";
    public String label = "#565f89";
    public String value = "#c0caf5";
    public String good = "#9ece6a";
    public String warn = "#e0af68";
    public String bad = "#f7768e";

    @Comment("Command feedback.")
    public String noPermission = "<prefix><bad>You do not have permission to do that.</bad>";
    public String playerNotFound = "<prefix><bad>No player matching <value><query></value> has ever connected.</bad>";
    public String usage = "<prefix><label>Usage: <value><usage></value></label>";
    public String reloaded = "<prefix><good>Configuration reloaded in <value><ms>ms</value>.</good>";
    public String reloadFailed = "<prefix><bad>Reload failed: <error>. The previous configuration is still active.</bad>";
    public String working = "<prefix><label>Working…</label>";
    public String noResults = "<prefix><label>Nothing to show.</label>";
    public String invalidNumber = "<prefix><bad><value><input></value> is not a number.</bad>";
    public String featureDisabled = "<prefix><warn><feature> is disabled in the configuration.</warn>";

    @Comment("Data management.")
    public String forgetConfirm =
            "<prefix><warn>This erases every record of <value><player></value> and cannot be undone.\n"
                    + "Run <value>/joinstats forget <player> confirm</value> to proceed.</warn>";
    public String forgetDone = "<prefix><good>Erased <value><rows></value> rows for <value><player></value>.</good>";
    public String exportDone = "<prefix><good>Exported <value><rows></value> rows to <value><file></value>.</good>";
    public String exportFailed = "<prefix><bad>Export failed: <error></bad>";
    public String noteAdded = "<prefix><good>Note added to <value><player></value>.</good>";
    public String tagAdded = "<prefix><good>Tagged <value><player></value> as <value><tag></value>.</good>";
    public String tagRemoved = "<prefix><good>Removed tag <value><tag></value> from <value><player></value>.</good>";

    @Comment("Alerts shown to staff holding joinstatistics.alerts.")
    public Map<String, String> alerts = new LinkedHashMap<>(Map.of(
            "alt-detected",
            "<prefix><warn><value><player></value> shares an address with <value><others></value>.</warn>",
            "vpn-detected",
            "<prefix><warn><value><player></value> connected through a <value><kind></value> "
                    + "(<value><network></value>).</warn>",
            "impossible-travel",
            "<prefix><bad><value><player></value> moved <value><distance>km</value> in "
                    + "<value><elapsed></value> — <value><speed> km/h</value>.</bad>",
            "rapid-rejoin",
            "<prefix><warn><value><player></value> reconnected <value><count></value> times in "
                    + "<value><window></value>.</warn>",
            "first-join",
            "<prefix><good><value><player></value> joined for the first time, from "
                    + "<value><country></value>.</good>",
            "name-change",
            "<prefix><warn><value><player></value> was previously known as <value><previous></value>.</warn>",
            "long-session",
            "<prefix><warn><value><player></value> has been online for <value><duration></value>.</warn>",
            "population-peak",
            "<prefix><good>New concurrent record: <value><count></value> players.</good>"));

    @Comment("The header used by every paginated list.")
    public String pageHeader =
            "<dark_gray><strikethrough>            </strikethrough></dark_gray> "
                    + "<accent><title></accent> <label>(page <page>/<pages>)</label> "
                    + "<dark_gray><strikethrough>            </strikethrough></dark_gray>";
    public String pageFooter = "<label>Showing <shown> of <total>.</label>";

    private transient MiniMessage miniMessage;

    /** Renders a message, substituting {@code <name>} placeholders from the given pairs. */
    public Component render(String template, Object... keyValuePairs) {
        TagResolver.Builder resolvers = TagResolver.builder();
        resolvers.resolver(Placeholder.parsed("prefix", prefix));
        resolvers.resolver(styleTag("accent", accent));
        resolvers.resolver(styleTag("accent2", accentAlt));
        resolvers.resolver(styleTag("label", label));
        resolvers.resolver(styleTag("value", value));
        resolvers.resolver(styleTag("good", good));
        resolvers.resolver(styleTag("warn", warn));
        resolvers.resolver(styleTag("bad", bad));
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            Object raw = keyValuePairs[i + 1];
            resolvers.resolver(Placeholder.unparsed(
                    String.valueOf(keyValuePairs[i]), raw == null ? "" : String.valueOf(raw)));
        }
        return mini().deserialize(template, resolvers.build());
    }

    /**
     * Exposes a colour from this file as a MiniMessage tag pair, so templates can write
     * {@code <good>…</good>} without repeating the hex code.
     */
    private TagResolver styleTag(String name, String colour) {
        return TagResolver.resolver(name, (args, context) ->
                net.kyori.adventure.text.minimessage.tag.Tag.styling(builder ->
                        builder.color(net.kyori.adventure.text.format.TextColor.fromCSSHexString(
                                colour.startsWith("#") ? colour : "#" + colour))));
    }

    public MiniMessage mini() {
        if (miniMessage == null) {
            miniMessage = MiniMessage.miniMessage();
        }
        return miniMessage;
    }

    /** Looks up an alert template, falling back to a plain rendering of the alert body. */
    public String alertTemplate(String type) {
        String template = alerts.get(type);
        return template == null ? "<prefix><warn><message></warn>" : template;
    }
}
