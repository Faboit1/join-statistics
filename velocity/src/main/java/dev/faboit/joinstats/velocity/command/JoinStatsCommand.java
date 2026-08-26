package dev.faboit.joinstats.velocity.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import dev.faboit.joinstats.velocity.JoinStatistics;
import dev.faboit.joinstats.velocity.config.Messages;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.export.ExportService;
import dev.faboit.joinstats.velocity.storage.dao.PlayerDao;
import dev.faboit.joinstats.velocity.storage.dao.PopulationDao;
import dev.faboit.joinstats.velocity.storage.model.PlayerProfile;
import dev.faboit.joinstats.velocity.storage.model.Records;
import dev.faboit.joinstats.velocity.util.Addresses;
import dev.faboit.joinstats.velocity.util.Durations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The {@code /joinstats} command tree.
 *
 * <p>Every subcommand follows the same shape: check the permission in {@code requires} so the
 * branch simply does not exist for staff who cannot use it, resolve arguments, then kick off the
 * query and return. Nothing here waits on the database — the reply is sent from the future's
 * callback, so a slow query delays one person's output rather than the proxy's command thread.
 */
public final class JoinStatsCommand {

    /** Permission nodes, gathered here so the README and the code cannot drift apart. */
    public static final String PERM_BASE = "joinstatistics.command";
    public static final String PERM_LOOKUP = "joinstatistics.lookup";
    public static final String PERM_SESSIONS = "joinstatistics.sessions";
    public static final String PERM_ALTS = "joinstatistics.alts";
    public static final String PERM_ADDRESS = "joinstatistics.address";
    public static final String PERM_CHAT = "joinstatistics.chatlog";
    public static final String PERM_ALERTS = "joinstatistics.alerts.view";
    public static final String PERM_NOTES = "joinstatistics.notes";
    public static final String PERM_EXPORT = "joinstatistics.export";
    public static final String PERM_ADMIN = "joinstatistics.admin";
    public static final String PERM_SELF = "joinstatistics.self";

    private final JoinStatistics plugin;

    private JoinStatsCommand(JoinStatistics plugin) {
        this.plugin = plugin;
    }

    /** Builds the tree and registers it under the configured name and aliases. */
    public static void register(JoinStatistics plugin) {
        JoinStatsCommand command = new JoinStatsCommand(plugin);
        BrigadierCommand brigadier = new BrigadierCommand(command.build());
        CommandMeta meta = plugin.proxy().getCommandManager()
                .metaBuilder(brigadier)
                .aliases(plugin.config().commands.aliases.toArray(String[]::new))
                .plugin(plugin)
                .build();
        plugin.proxy().getCommandManager().register(meta, brigadier);
    }

    private LiteralArgumentBuilder<CommandSource> build() {
        return literal("joinstats")
                .requires(source -> source.hasPermission(PERM_BASE)
                        || source.hasPermission(PERM_SELF))
                .executes(context -> help(context.getSource()))
                .then(lookup())
                .then(sessions())
                .then(alts())
                .then(addressLookup())
                .then(placeholders())
                .then(top())
                .then(online())
                .then(counts())
                .then(activity())
                .then(servers())
                .then(events())
                .then(chat())
                .then(commands())
                .then(alerts())
                .then(search())
                .then(notes())
                .then(tags())
                .then(overview())
                .then(status())
                .then(export())
                .then(prune())
                .then(forget())
                .then(self())
                .then(reload());
    }

    // ------------------------------------------------------------------ lookup

    private LiteralArgumentBuilder<CommandSource> lookup() {
        return literal("lookup")
                .requires(permission(PERM_LOOKUP))
                .then(playerArgument().executes(context ->
                        showProfile(context.getSource(), argument(context, "player"))));
    }

    private LiteralArgumentBuilder<CommandSource> self() {
        return literal("me")
                .requires(source -> source instanceof Player && source.hasPermission(PERM_SELF))
                .executes(context -> {
                    if (!plugin.config().privacy.selfServiceLookup) {
                        send(context.getSource(), messages().featureDisabled,
                                "feature", "Self-service lookup");
                        return 1;
                    }
                    Player player = (Player) context.getSource();
                    return showProfile(context.getSource(), player.getUniqueId().toString());
                });
    }

    private int showProfile(CommandSource source, String query) {
        Rendering render = rendering();
        boolean maySeeAddress = source.hasPermission(PERM_ADDRESS);

        plugin.profiles().full(query, 5, 30).thenAccept(found -> {
            if (found.isEmpty()) {
                send(source, messages().playerNotFound, "query", query);
                return;
            }
            Records.FullProfile full = found.get();
            UUID uuid = full.profile().uuid();
            boolean online = plugin.proxy().getPlayer(uuid).isPresent();
            long liveMillis = plugin.sessions().session(uuid)
                    .map(session -> session.connectedMillis(System.currentTimeMillis()))
                    .orElse(0L);

            source.sendMessage(render.header("Profile"));
            for (Component line : render.profile(full, maySeeAddress,
                    (address, allowed) -> plugin.privacy().displayAddress(address, allowed),
                    online, liveMillis)) {
                source.sendMessage(line);
            }
            source.sendMessage(profileActions(full.profile().username()));
        }).exceptionally(reportTo(source, "look up " + query));
        return 1;
    }

    /** The row of follow-up commands shown under a profile. */
    private Component profileActions(String username) {
        Rendering render = rendering();
        TextComponent.Builder builder = Component.text().append(Component.text("  ", render.label()));
        String[][] actions = {
                {"sessions", "/joinstats sessions " + username},
                {"alts", "/joinstats alts " + username},
                {"activity", "/joinstats activity " + username},
                {"placeholders", "/joinstats papi " + username},
                {"events", "/joinstats events player " + username},
        };
        for (int i = 0; i < actions.length; i++) {
            if (i > 0) {
                builder.append(Component.text(" · ", render.label()));
            }
            builder.append(Component.text("[" + actions[i][0] + "]", render.accent())
                    .clickEvent(ClickEvent.runCommand(actions[i][1]))
                    .hoverEvent(HoverEvent.showText(
                            Component.text(actions[i][1], render.label()))));
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ sessions

    private LiteralArgumentBuilder<CommandSource> sessions() {
        return literal("sessions")
                .requires(permission(PERM_SESSIONS))
                .then(playerArgument()
                        .executes(context -> showSessions(context.getSource(),
                                argument(context, "player"), 1))
                        .then(pageArgument().executes(context -> showSessions(context.getSource(),
                                argument(context, "player"),
                                IntegerArgumentType.getInteger(context, "page")))));
    }

    private int showSessions(CommandSource source, String query, int page) {
        Rendering render = rendering();
        int size = pageSize();
        int offset = Math.max(0, (page - 1) * size);

        withProfile(source, query, profile ->
                plugin.sessionDao().recent(profile.uuid(), size, offset).thenAccept(rows -> {
                    int pages = Math.max(1, (profile.sessions() + size - 1) / size);
                    source.sendMessage(render.header(
                            profile.username() + "'s sessions", page, pages));
                    if (rows.isEmpty()) {
                        send(source, messages().noResults);
                        return;
                    }
                    rows.forEach(session -> source.sendMessage(render.sessionLine(session)));
                    source.sendMessage(render.pageControls(
                            "/joinstats sessions " + profile.username(), page, pages));
                }).exceptionally(reportTo(source, "read sessions")));
        return 1;
    }

    // ------------------------------------------------------------------ alts and addresses

    private LiteralArgumentBuilder<CommandSource> alts() {
        return literal("alts")
                .requires(permission(PERM_ALTS))
                .then(playerArgument().executes(context ->
                        showAlts(context.getSource(), argument(context, "player"))));
    }

    private int showAlts(CommandSource source, String query) {
        Rendering render = rendering();
        PluginConfig.Alerts settings = plugin.config().alerts;
        long since = System.currentTimeMillis() - settings.altWindow().toMillis();

        withProfile(source, query, profile ->
                plugin.addresses().findAlts(profile.uuid(), since, settings.altMatchSubnet, 50)
                        .thenAccept(alts -> {
                            source.sendMessage(render.header(
                                    "Accounts sharing an address with " + profile.username()));
                            if (alts.isEmpty()) {
                                source.sendMessage(render.note("None within the last "
                                        + Durations.format(settings.altWindow().toMillis()) + "."));
                                return;
                            }
                            for (Records.AltAccount alt : alts) {
                                source.sendMessage(Component.text()
                                        .append(Component.text("  ", render.label()))
                                        .append(render.playerLink(alt.username()))
                                        .append(Component.text("  " + alt.sharedAddresses()
                                                + (alt.exactMatch() ? " shared address"
                                                        : " shared subnet")
                                                + (alt.sharedAddresses() == 1 ? "" : "es"),
                                                render.label()))
                                        .append(Component.text("  last ", render.label()))
                                        .append(render.when(alt.lastShared()))
                                        .build());
                            }
                            source.sendMessage(render.note(
                                    "A shared address means a shared household, school or "
                                            + "carrier-grade NAT just as often as a second "
                                            + "account. Treat it as a lead, not a verdict."));
                        }).exceptionally(reportTo(source, "search for shared addresses")));
        return 1;
    }

    private LiteralArgumentBuilder<CommandSource> addressLookup() {
        return literal("ip")
                .requires(permission(PERM_ADDRESS))
                .then(arg("address", StringArgumentType.word()).executes(context -> {
                    CommandSource source = context.getSource();
                    String raw = StringArgumentType.getString(context, "address");
                    String stored = plugin.privacy().storedAddress(raw);
                    Rendering render = rendering();

                    plugin.addresses().find(stored).thenAccept(record -> {
                        source.sendMessage(render.header("Address " + Addresses.mask(raw)));
                        if (record.isEmpty()) {
                            send(source, messages().noResults);
                            return;
                        }
                        var address = record.get();
                        source.sendMessage(render.row("seen", Component.text()
                                .append(Component.text(address.hits() + " times", render.value()))
                                .append(Component.text(", first ", render.label()))
                                .append(render.when(address.firstSeen()))
                                .append(Component.text(", last ", render.label()))
                                .append(render.when(address.lastSeen()))
                                .build()));
                        source.sendMessage(render.row("location", address.describeLocation()));
                        source.sendMessage(render.row("network", Component.text()
                                .append(Component.text(address.networkKind(),
                                        address.anonymised() ? render.warn() : render.value()))
                                .append(Component.text(address.asName() == null ? ""
                                        : "  " + address.asName(), render.label()))
                                .build()));
                        if (address.hostname() != null) {
                            source.sendMessage(render.row("hostname", address.hostname()));
                        }
                        if (address.timezone() != null) {
                            source.sendMessage(render.row("timezone", address.timezone()));
                        }
                        plugin.addresses().playersOn(stored).thenAccept(users -> {
                            source.sendMessage(render.row("accounts",
                                    Component.text(String.valueOf(users.size()), render.value())));
                            for (Records.AltAccount user : users) {
                                source.sendMessage(Component.text()
                                        .append(Component.text("    ", render.label()))
                                        .append(render.playerLink(user.username()))
                                        .append(Component.text("  last ", render.label()))
                                        .append(render.when(user.lastShared()))
                                        .build());
                            }
                        });
                    }).exceptionally(reportTo(source, "look up that address"));
                    return 1;
                }));
    }

    // ------------------------------------------------------------------ placeholders

    private LiteralArgumentBuilder<CommandSource> placeholders() {
        return literal("papi")
                .requires(permission(PERM_LOOKUP))
                .then(playerArgument()
                        .executes(context -> showPlaceholders(context.getSource(),
                                argument(context, "player")))
                        .then(literal("history")
                                .then(arg("placeholder", StringArgumentType.string())
                                        .suggests(placeholderSuggestions())
                                        .executes(context -> showPlaceholderHistory(
                                                context.getSource(), argument(context, "player"),
                                                StringArgumentType.getString(context,
                                                        "placeholder"))))))
                .then(literal("refresh")
                        .requires(permission(PERM_ADMIN))
                        .executes(context -> {
                            if (!plugin.placeholders().enabled()) {
                                send(context.getSource(), messages().featureDisabled,
                                        "feature", "Placeholder tracking");
                                return 1;
                            }
                            plugin.placeholders().refreshAll();
                            context.getSource().sendMessage(rendering().note(
                                    "Requested a refresh for every online player."));
                            return 1;
                        }));
    }

    private int showPlaceholders(CommandSource source, String query) {
        Rendering render = rendering();
        withProfile(source, query, profile ->
                plugin.placeholderDao().current(profile.uuid()).thenAccept(values -> {
                    source.sendMessage(render.header(profile.username() + "'s placeholders"));
                    if (values.isEmpty()) {
                        source.sendMessage(render.note(plugin.placeholders().enabled()
                                ? "Nothing stored yet. Values arrive once the player is on a "
                                        + "backend running the companion plugin."
                                : "Placeholder tracking is disabled in the configuration."));
                        return;
                    }
                    for (Records.PlaceholderValue value : values) {
                        source.sendMessage(Component.text()
                                .append(Component.text("  " + value.placeholder() + "  ",
                                        render.label()))
                                .append(Component.text(
                                        value.value() == null || value.value().isBlank()
                                                ? "(unresolved)" : value.value(),
                                        value.value() == null || value.value().isBlank()
                                                ? render.warn() : render.value()))
                                .build()
                                .hoverEvent(HoverEvent.showText(Component.text(
                                        "from " + value.server() + ", updated "
                                                + render.timestamp(value.updatedAt()),
                                        render.label()))));
                    }
                }).exceptionally(reportTo(source, "read placeholders")));
        return 1;
    }

    private int showPlaceholderHistory(CommandSource source, String query, String placeholder) {
        Rendering render = rendering();
        withProfile(source, query, profile ->
                plugin.placeholderDao().history(profile.uuid(), placeholder, pageSize() * 2)
                        .thenAccept(points -> {
                            source.sendMessage(render.header(placeholder + " · "
                                    + profile.username()));
                            if (points.isEmpty()) {
                                send(source, messages().noResults);
                                return;
                            }
                            for (Records.PlaceholderPoint point : points) {
                                source.sendMessage(Component.text()
                                        .append(Component.text("  "
                                                + render.timestamp(point.at()) + "  ",
                                                render.label()))
                                        .append(Component.text(String.valueOf(point.value()),
                                                render.value()))
                                        .build());
                            }
                        }).exceptionally(reportTo(source, "read placeholder history")));
        return 1;
    }

    // ------------------------------------------------------------------ leaderboards

    private LiteralArgumentBuilder<CommandSource> top() {
        LiteralArgumentBuilder<CommandSource> node = literal("top")
                .requires(permission(PERM_LOOKUP))
                .executes(context -> showTop(context.getSource(), PlayerDao.Metric.PLAYTIME, 1))
                .then(arg("metric", StringArgumentType.word())
                        .suggests(metricSuggestions())
                        .executes(context -> {
                            PlayerDao.Metric metric = PlayerDao.Metric.of(
                                    StringArgumentType.getString(context, "metric"));
                            if (metric == null) {
                                send(context.getSource(), messages().usage,
                                        "usage", "/joinstats top <" + metricNames() + ">");
                                return 1;
                            }
                            return showTop(context.getSource(), metric, 1);
                        })
                        .then(pageArgument().executes(context -> {
                            PlayerDao.Metric metric = PlayerDao.Metric.of(
                                    StringArgumentType.getString(context, "metric"));
                            if (metric == null) {
                                send(context.getSource(), messages().usage,
                                        "usage", "/joinstats top <" + metricNames() + ">");
                                return 1;
                            }
                            return showTop(context.getSource(), metric,
                                    IntegerArgumentType.getInteger(context, "page"));
                        })));

        return node.then(literal("papi")
                .then(arg("placeholder", StringArgumentType.string())
                        .suggests(placeholderSuggestions())
                        .executes(context -> {
                            CommandSource source = context.getSource();
                            String placeholder =
                                    StringArgumentType.getString(context, "placeholder");
                            Rendering render = rendering();
                            plugin.placeholderDao().rankNumeric(placeholder, pageSize())
                                    .thenAccept(rows -> {
                                        source.sendMessage(render.header("Top " + placeholder));
                                        if (rows.isEmpty()) {
                                            source.sendMessage(render.note(
                                                    "No numeric values stored for that "
                                                            + "placeholder."));
                                            return;
                                        }
                                        rows.forEach(row ->
                                                source.sendMessage(rankLine(render, row)));
                                    }).exceptionally(reportTo(source, "rank that placeholder"));
                            return 1;
                        })));
    }

    private int showTop(CommandSource source, PlayerDao.Metric metric, int page) {
        Rendering render = rendering();
        int size = pageSize();
        int offset = Math.max(0, (page - 1) * size);

        plugin.players().top(metric, size, offset).thenAccept(rows -> {
            source.sendMessage(render.header("Top by " + metric.display(), page,
                    rows.size() < size ? page : page + 1));
            if (rows.isEmpty()) {
                send(source, messages().noResults);
                return;
            }
            rows.forEach(row -> source.sendMessage(rankLine(render, row)));
            source.sendMessage(render.pageControls(
                    "/joinstats top " + metric.name().toLowerCase(Locale.ROOT), page,
                    rows.size() < size ? page : page + 1));
        }).exceptionally(reportTo(source, "build that leaderboard"));
        return 1;
    }

    private Component rankLine(Rendering render, Records.Ranked row) {
        return Component.text()
                .append(Component.text(String.format(Locale.ROOT, "  %2d. ", row.rank()),
                        render.label()))
                .append(render.playerLink(row.username()))
                .append(Component.text("  " + row.display(), render.accent()))
                .build();
    }

    // ------------------------------------------------------------------ live views

    private LiteralArgumentBuilder<CommandSource> online() {
        return literal("online")
                .requires(permission(PERM_LOOKUP))
                .executes(context -> {
                    CommandSource source = context.getSource();
                    Rendering render = rendering();
                    long now = System.currentTimeMillis();

                    List<Player> players = new ArrayList<>(plugin.proxy().getAllPlayers());
                    source.sendMessage(render.header("Online — " + players.size()));
                    if (players.isEmpty()) {
                        send(source, messages().noResults);
                        return 1;
                    }
                    players.sort(java.util.Comparator.comparing(Player::getUsername,
                            String.CASE_INSENSITIVE_ORDER));

                    for (Player player : players) {
                        long connected = plugin.sessions().session(player.getUniqueId())
                                .map(session -> session.connectedMillis(now)).orElse(0L);
                        source.sendMessage(Component.text()
                                .append(Component.text("  ", render.label()))
                                .append(render.playerLink(player.getUsername()))
                                .append(Component.text("  " + Durations.format(connected),
                                        render.accent()))
                                .append(Component.text("  " + player.getCurrentServer()
                                        .map(connection ->
                                                connection.getServerInfo().getName())
                                        .orElse("(routing)"), render.value()))
                                .append(Component.text("  " + player.getPing() + "ms",
                                        render.label()))
                                .build());
                    }
                    int lingering = plugin.sessions().all().size() - players.size();
                    if (lingering > 0) {
                        source.sendMessage(render.note(lingering + " session(s) waiting out the "
                                + "rejoin grace window."));
                    }
                    return 1;
                });
    }

    private LiteralArgumentBuilder<CommandSource> counts() {
        return literal("counts")
                .requires(permission(PERM_LOOKUP))
                .executes(context -> showCounts(context.getSource(), "1h"))
                .then(arg("window", StringArgumentType.word())
                        .suggests(suggest("15m", "1h", "6h", "24h", "7d", "30d"))
                        .executes(context -> showCounts(context.getSource(),
                                StringArgumentType.getString(context, "window"))));
    }

    private int showCounts(CommandSource source, String window) {
        Rendering render = rendering();
        Duration span = Durations.parse(window, Duration.ofHours(1));
        long now = System.currentTimeMillis();
        long from = now - span.toMillis();

        if (!plugin.config().population.enabled) {
            send(source, messages().featureDisabled, "feature", "Population sampling");
            return 1;
        }

        plugin.population().summary(from, now).thenAccept(summary ->
                plugin.population().samples(from, now, 5000).thenAccept(samples -> {
                    source.sendMessage(render.header(
                            "Players over the last " + Durations.format(span.toMillis())));
                    source.sendMessage(render.sparkline(samples, 48));
                    source.sendMessage(render.row("now", Component.text(
                            String.valueOf(plugin.proxy().getPlayerCount()), render.value())));
                    source.sendMessage(render.row("average", Component.text(
                            String.format(Locale.ROOT, "%.1f", summary.average()),
                            render.value())));
                    source.sendMessage(render.row("range", Component.text(
                            summary.minimum() + " – " + summary.maximum(), render.value())));
                    source.sendMessage(render.row("samples", Component.text(
                            String.valueOf(summary.samples()), render.value())));

                    plugin.population().peak().thenAccept(peak -> source.sendMessage(
                            render.row("all-time peak", peak.total() <= 0
                                    // Before anyone has ever connected the highest sample is
                                    // zero, and timestamping that reads as a real observation.
                                    ? Component.text("nobody has connected yet", render.label())
                                    : Component.text()
                                            .append(Component.text(String.valueOf(peak.total()),
                                                    render.good()))
                                            .append(Component.text("  ", render.label()))
                                            .append(render.when(peak.at()))
                                            .build())));

                    plugin.population().latestBreakdown(PopulationDao.SCOPE_SERVER)
                            .thenAccept(perServer -> {
                                if (perServer.isEmpty()) {
                                    return;
                                }
                                source.sendMessage(Component.text("  by server",
                                        render.label(), TextDecoration.BOLD));
                                perServer.forEach(entry -> source.sendMessage(Component.text(
                                        "    " + entry.key() + "  " + entry.count(),
                                        render.value())));
                            });
                }).exceptionally(reportTo(source, "read population samples")))
                .exceptionally(reportTo(source, "summarise the population"));
        return 1;
    }

    private LiteralArgumentBuilder<CommandSource> activity() {
        return literal("activity")
                .requires(permission(PERM_LOOKUP))
                .executes(context -> {
                    CommandSource source = context.getSource();
                    Rendering render = rendering();
                    plugin.sessionDao().networkActivity().thenAccept(cells -> {
                        source.sendMessage(render.header("When the network is busy"));
                        render.heatmap(cells).forEach(source::sendMessage);
                    }).exceptionally(reportTo(source, "build the activity heatmap"));
                    return 1;
                })
                .then(playerArgument().executes(context -> {
                    CommandSource source = context.getSource();
                    Rendering render = rendering();
                    withProfile(source, argument(context, "player"), profile ->
                            plugin.sessionDao().hourlyActivity(profile.uuid()).thenAccept(cells -> {
                                source.sendMessage(render.header(
                                        "When " + profile.username() + " plays"));
                                render.heatmap(cells).forEach(source::sendMessage);
                            }).exceptionally(reportTo(source, "build the activity heatmap")));
                    return 1;
                }));
    }

    private LiteralArgumentBuilder<CommandSource> servers() {
        return literal("servers")
                .requires(permission(PERM_LOOKUP))
                .executes(context -> {
                    CommandSource source = context.getSource();
                    Rendering render = rendering();
                    plugin.sessionDao().serverTotals(25).thenAccept(rows -> {
                        source.sendMessage(render.header("Playtime by backend"));
                        if (rows.isEmpty()) {
                            send(source, messages().noResults);
                            return;
                        }
                        rows.forEach(row -> source.sendMessage(Component.text()
                                .append(Component.text("  " + row.key() + "  ", render.value()))
                                .append(Component.text(row.display(), render.accent()))
                                .build()));
                        var companions = plugin.bridge().companions();
                        if (!companions.isEmpty()) {
                            source.sendMessage(render.note("companion installed on: "
                                    + String.join(", ", companions.keySet())));
                        }
                    }).exceptionally(reportTo(source, "read server playtime"));
                    return 1;
                });
    }

    // ------------------------------------------------------------------ logs

    private LiteralArgumentBuilder<CommandSource> events() {
        return literal("events")
                .requires(permission(PERM_LOOKUP))
                .executes(context -> showEvents(context.getSource(), null, null, 1))
                .then(literal("player").then(playerArgument()
                        .executes(context -> showEvents(context.getSource(),
                                argument(context, "player"), null, 1))
                        .then(pageArgument().executes(context -> showEvents(context.getSource(),
                                argument(context, "player"), null,
                                IntegerArgumentType.getInteger(context, "page"))))))
                .then(literal("type").then(arg("type", StringArgumentType.word())
                        .suggests(suggest("login", "rejoin", "disconnect", "session-end",
                                "server-join", "server-switch", "kick", "connect-attempt",
                                "login-denied", "mods"))
                        .executes(context -> showEvents(context.getSource(), null,
                                StringArgumentType.getString(context, "type"), 1))
                        .then(pageArgument().executes(context -> showEvents(context.getSource(),
                                null, StringArgumentType.getString(context, "type"),
                                IntegerArgumentType.getInteger(context, "page"))))));
    }

    private int showEvents(CommandSource source, String player, String type, int page) {
        Rendering render = rendering();
        int size = pageSize();
        int offset = Math.max(0, (page - 1) * size);

        Consumer<List<Records.Event>> display = rows -> {
            source.sendMessage(render.header("Events", page, rows.size() < size ? page : page + 1));
            if (rows.isEmpty()) {
                send(source, messages().noResults);
                return;
            }
            for (Records.Event event : rows) {
                source.sendMessage(Component.text()
                        .append(Component.text("  " + render.clock(event.at()) + " ",
                                render.label()))
                        .append(Component.text(event.type(), render.accent()))
                        .append(Component.text(event.username() == null ? ""
                                : "  " + event.username(), render.value()))
                        .append(Component.text(event.server() == null ? ""
                                : "  → " + event.server(), render.label()))
                        .build()
                        .hoverEvent(HoverEvent.showText(Component.text(
                                render.timestamp(event.at())
                                        + (event.detail() == null ? "" : "\n" + event.detail())
                                        + (event.data() == null ? "" : "\n" + event.data()),
                                render.label()))));
            }
        };

        if (player == null) {
            plugin.events().recent(type, size, offset).thenAccept(display)
                    .exceptionally(reportTo(source, "read the event log"));
        } else {
            withProfile(source, player, profile ->
                    plugin.events().forPlayer(profile.uuid(), size, offset).thenAccept(display)
                            .exceptionally(reportTo(source, "read the event log")));
        }
        return 1;
    }

    private LiteralArgumentBuilder<CommandSource> chat() {
        return literal("chat")
                .requires(permission(PERM_CHAT))
                .then(playerArgument()
                        .executes(context -> showChat(context.getSource(),
                                argument(context, "player"), 1))
                        .then(pageArgument().executes(context -> showChat(context.getSource(),
                                argument(context, "player"),
                                IntegerArgumentType.getInteger(context, "page")))));
    }

    private int showChat(CommandSource source, String query, int page) {
        Rendering render = rendering();
        int size = pageSize();
        withProfile(source, query, profile ->
                plugin.events().chatOf(profile.uuid(), size, Math.max(0, (page - 1) * size))
                        .thenAccept(rows -> {
                            source.sendMessage(render.header(profile.username() + "'s chat", page,
                                    rows.size() < size ? page : page + 1));
                            if (rows.isEmpty()) {
                                send(source, messages().noResults);
                                return;
                            }
                            for (Records.ChatLine line : rows) {
                                source.sendMessage(Component.text()
                                        .append(Component.text("  " + render.clock(line.at())
                                                + " ", render.label()))
                                        .append(Component.text(line.message() == null
                                                        ? "(" + line.length()
                                                                + " characters, not stored)"
                                                        : line.message(),
                                                line.cancelled() ? render.label() : render.value()))
                                        .append(Component.text(line.cancelled()
                                                ? "  (blocked)" : "", render.warn()))
                                        .build());
                            }
                            source.sendMessage(render.pageControls(
                                    "/joinstats chat " + profile.username(), page,
                                    rows.size() < size ? page : page + 1));
                        }).exceptionally(reportTo(source, "read the chat log")));
        return 1;
    }

    private LiteralArgumentBuilder<CommandSource> commands() {
        return literal("commands")
                .requires(permission(PERM_CHAT))
                .executes(context -> {
                    CommandSource source = context.getSource();
                    Rendering render = rendering();
                    plugin.events().topCommands(pageSize()).thenAccept(rows -> {
                        source.sendMessage(render.header("Most-used commands"));
                        rows.forEach(row -> source.sendMessage(Component.text(
                                "  " + row.key() + "  " + row.count(), render.value())));
                    }).exceptionally(reportTo(source, "read command usage"));
                    return 1;
                })
                .then(playerArgument().executes(context -> {
                    CommandSource source = context.getSource();
                    Rendering render = rendering();
                    withProfile(source, argument(context, "player"), profile ->
                            plugin.events().commandsOf(profile.uuid(), pageSize(), 0)
                                    .thenAccept(rows -> {
                                        source.sendMessage(render.header(
                                                profile.username() + "'s commands"));
                                        if (rows.isEmpty()) {
                                            send(source, messages().noResults);
                                            return;
                                        }
                                        for (Records.CommandLine line : rows) {
                                            source.sendMessage(Component.text()
                                                    .append(Component.text("  "
                                                            + render.clock(line.at()) + " ",
                                                            render.label()))
                                                    .append(Component.text("/" + line.command(),
                                                            render.accent()))
                                                    .append(Component.text(
                                                            line.arguments() == null ? ""
                                                                    : " " + line.arguments(),
                                                            render.value()))
                                                    .build());
                                        }
                                    }).exceptionally(reportTo(source, "read command history")));
                    return 1;
                }));
    }

    private LiteralArgumentBuilder<CommandSource> alerts() {
        return literal("alerts")
                .requires(permission(PERM_ALERTS))
                .executes(context -> showAlerts(context.getSource(), null, 1))
                .then(literal("ack").executes(context -> {
                    plugin.annotations().acknowledgeAll();
                    context.getSource().sendMessage(
                            rendering().note("Acknowledged every open alert."));
                    return 1;
                }))
                .then(arg("type", StringArgumentType.word())
                        .suggests(suggest("alt-detected", "vpn-detected", "impossible-travel",
                                "rapid-rejoin", "first-join", "name-change", "long-session",
                                "population-peak"))
                        .executes(context -> showAlerts(context.getSource(),
                                StringArgumentType.getString(context, "type"), 1))
                        .then(pageArgument().executes(context -> showAlerts(context.getSource(),
                                StringArgumentType.getString(context, "type"),
                                IntegerArgumentType.getInteger(context, "page")))));
    }

    private int showAlerts(CommandSource source, String type, int page) {
        Rendering render = rendering();
        int size = pageSize();
        plugin.annotations().recentAlerts(type, size, Math.max(0, (page - 1) * size))
                .thenAccept(rows -> {
                    source.sendMessage(render.header("Alerts", page,
                            rows.size() < size ? page : page + 1));
                    if (rows.isEmpty()) {
                        send(source, messages().noResults);
                        return;
                    }
                    for (Records.Alert alert : rows) {
                        source.sendMessage(Component.text()
                                .append(Component.text("  " + render.clock(alert.at()) + " ",
                                        render.label()))
                                .append(Component.text(alert.type(), severityColour(render,
                                        alert.severity())))
                                .append(Component.text("  " + alert.message(), render.value()))
                                .build()
                                .hoverEvent(HoverEvent.showText(Component.text(
                                        render.timestamp(alert.at())
                                                + (alert.data() == null ? ""
                                                        : "\n" + alert.data()),
                                        render.label()))));
                    }
                }).exceptionally(reportTo(source, "read alerts"));
        return 1;
    }

    private net.kyori.adventure.text.format.TextColor severityColour(Rendering render,
                                                                     String severity) {
        return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "serious" -> render.bad();
            case "warning" -> render.warn();
            case "good" -> render.good();
            default -> render.accent();
        };
    }

    private LiteralArgumentBuilder<CommandSource> search() {
        return literal("search")
                .requires(permission(PERM_LOOKUP))
                .then(arg("query", StringArgumentType.word()).executes(context -> {
                    CommandSource source = context.getSource();
                    Rendering render = rendering();
                    String query = StringArgumentType.getString(context, "query");
                    plugin.players().search(query, pageSize() * 2).thenAccept(rows -> {
                        source.sendMessage(render.header("Search: " + query));
                        if (rows.isEmpty()) {
                            send(source, messages().noResults);
                            return;
                        }
                        for (PlayerProfile profile : rows) {
                            source.sendMessage(Component.text()
                                    .append(Component.text("  ", render.label()))
                                    .append(render.playerLink(profile.username()))
                                    .append(Component.text("  last seen ", render.label()))
                                    .append(render.when(profile.lastSeen()))
                                    .append(Component.text("  "
                                            + Durations.format(profile.playtime()),
                                            render.accent()))
                                    .build());
                        }
                    }).exceptionally(reportTo(source, "run that search"));
                    return 1;
                }));
    }

    // ------------------------------------------------------------------ annotations

    private LiteralArgumentBuilder<CommandSource> notes() {
        return literal("note")
                .requires(permission(PERM_NOTES))
                .then(literal("add").then(playerArgument()
                        .then(arg("text", StringArgumentType.greedyString()).executes(context -> {
                            CommandSource source = context.getSource();
                            String text = StringArgumentType.getString(context, "text");
                            withProfile(source, argument(context, "player"), profile -> {
                                plugin.annotations().addNote(System.currentTimeMillis(),
                                        profile.uuid(), sourceName(source), sourceUuid(source),
                                        text);
                                send(source, messages().noteAdded,
                                        "player", profile.username());
                            });
                            return 1;
                        }))))
                .then(literal("list").then(playerArgument().executes(context -> {
                    CommandSource source = context.getSource();
                    Rendering render = rendering();
                    withProfile(source, argument(context, "player"), profile ->
                            plugin.annotations().notesFor(profile.uuid(), 50).thenAccept(rows -> {
                                source.sendMessage(render.header(
                                        "Notes on " + profile.username()));
                                if (rows.isEmpty()) {
                                    send(source, messages().noResults);
                                    return;
                                }
                                for (Records.Note note : rows) {
                                    source.sendMessage(Component.text()
                                            .append(Component.text("  #" + note.id() + "  ",
                                                    render.label()))
                                            .append(Component.text(note.note(), render.value()))
                                            .append(Component.text("  — " + note.author() + ", "
                                                    + render.timestamp(note.at()), render.label()))
                                            .build());
                                }
                            }).exceptionally(reportTo(source, "read notes")));
                    return 1;
                })))
                .then(literal("remove").then(arg("id", IntegerArgumentType.integer(1))
                        .executes(context -> {
                            plugin.annotations().removeNote(
                                    IntegerArgumentType.getInteger(context, "id"));
                            context.getSource().sendMessage(rendering().note("Note removed."));
                            return 1;
                        })));
    }

    private LiteralArgumentBuilder<CommandSource> tags() {
        return literal("tag")
                .requires(permission(PERM_NOTES))
                .then(literal("add").then(playerArgument()
                        .then(arg("tag", StringArgumentType.word())
                                .suggests(tagSuggestions())
                                .executes(context -> {
                                    CommandSource source = context.getSource();
                                    String tag = StringArgumentType.getString(context, "tag")
                                            .toLowerCase(Locale.ROOT);
                                    withProfile(source, argument(context, "player"), profile -> {
                                        plugin.annotations().addTag(profile.uuid(), tag,
                                                System.currentTimeMillis(), sourceName(source));
                                        send(source, messages().tagAdded,
                                                "player", profile.username(), "tag", tag);
                                    });
                                    return 1;
                                }))))
                .then(literal("remove").then(playerArgument()
                        .then(arg("tag", StringArgumentType.word())
                                .suggests(tagSuggestions())
                                .executes(context -> {
                                    CommandSource source = context.getSource();
                                    String tag = StringArgumentType.getString(context, "tag")
                                            .toLowerCase(Locale.ROOT);
                                    withProfile(source, argument(context, "player"), profile -> {
                                        plugin.annotations().removeTag(profile.uuid(), tag);
                                        send(source, messages().tagRemoved,
                                                "player", profile.username(), "tag", tag);
                                    });
                                    return 1;
                                }))))
                .then(literal("list").then(arg("tag", StringArgumentType.word())
                        .suggests(tagSuggestions())
                        .executes(context -> {
                            CommandSource source = context.getSource();
                            Rendering render = rendering();
                            String tag = StringArgumentType.getString(context, "tag")
                                    .toLowerCase(Locale.ROOT);
                            plugin.annotations().playersWithTag(tag, 50).thenAccept(rows -> {
                                source.sendMessage(render.header("Tagged '" + tag + "'"));
                                if (rows.isEmpty()) {
                                    send(source, messages().noResults);
                                    return;
                                }
                                rows.forEach(row -> source.sendMessage(rankLine(render, row)));
                            }).exceptionally(reportTo(source, "list tagged accounts"));
                            return 1;
                        })));
    }

    // ------------------------------------------------------------------ administration

    private LiteralArgumentBuilder<CommandSource> overview() {
        return literal("overview")
                .requires(permission(PERM_LOOKUP))
                .executes(context -> {
                    CommandSource source = context.getSource();
                    Rendering render = rendering();
                    long todayStart = dev.faboit.joinstats.velocity.util.Ticks.startOfDay(
                            System.currentTimeMillis(), plugin.zone());

                    plugin.maintenance().overview(todayStart).thenAccept(overview -> {
                        source.sendMessage(render.header("Network overview"));
                        source.sendMessage(render.row("accounts", Component.text(
                                String.valueOf(overview.players()), render.value())));
                        source.sendMessage(render.row("sessions", Component.text(
                                String.valueOf(overview.sessions()), render.value())));
                        source.sendMessage(render.row("total playtime",
                                render.duration(overview.totalPlaytime())));
                        source.sendMessage(render.row("addresses", Component.text(
                                String.valueOf(overview.addresses()), render.value())));
                        source.sendMessage(render.row("today", Component.text(
                                overview.activeToday() + " active, " + overview.newPlayersToday()
                                        + " new", render.value())));
                        source.sendMessage(render.row("peak online", Component.text()
                                .append(Component.text(String.valueOf(overview.peakOnline()),
                                        render.good()))
                                .append(Component.text("  ", render.label()))
                                .append(render.when(overview.peakOnlineAt()))
                                .build()));
                        source.sendMessage(render.row("logged", Component.text(
                                overview.events() + " events, " + overview.chatMessages()
                                        + " chat, " + overview.commands() + " commands",
                                render.value())));
                        source.sendMessage(render.row("database", Component.text(
                                bytes(overview.databaseBytes()), render.value())));

                        plugin.addresses().countries(8).thenAccept(countries -> {
                            if (countries.isEmpty()) {
                                return;
                            }
                            source.sendMessage(Component.text("  top countries", render.label(),
                                    TextDecoration.BOLD));
                            countries.forEach(entry -> source.sendMessage(Component.text(
                                    "    " + entry.key() + "  " + entry.count(), render.value())));
                        });
                        plugin.players().tally("version", 6).thenAccept(versions -> {
                            if (versions.isEmpty()) {
                                return;
                            }
                            source.sendMessage(Component.text("  client versions", render.label(),
                                    TextDecoration.BOLD));
                            versions.forEach(entry -> source.sendMessage(Component.text(
                                    "    " + entry.key() + "  " + entry.count(), render.value())));
                        });
                    }).exceptionally(reportTo(source, "build the overview"));
                    return 1;
                });
    }

    private LiteralArgumentBuilder<CommandSource> status() {
        return literal("status")
                .requires(permission(PERM_ADMIN))
                .executes(context -> {
                    CommandSource source = context.getSource();
                    Rendering render = rendering();

                    var writes = plugin.writes().stats();
                    var geo = plugin.geo().stats();
                    var bridge = plugin.bridge().stats();
                    var placeholders = plugin.placeholders().stats();
                    var webhooks = plugin.webhooks().stats();

                    source.sendMessage(render.header("JoinStatistics status"));
                    source.sendMessage(render.row("version",
                            dev.faboit.joinstats.velocity.BuildConstants.VERSION));
                    source.sendMessage(render.row("database",
                            plugin.database().file().toString()));
                    source.sendMessage(render.row("writer", Component.text(
                            writes.applied() + " applied in " + writes.commits() + " commits · "
                                    + writes.queued() + " queued",
                            writes.dropped() > 0 || writes.failures() > 0
                                    ? render.warn() : render.value())));
                    if (writes.dropped() > 0 || writes.failures() > 0) {
                        source.sendMessage(render.row("writer problems", Component.text(
                                writes.dropped() + " dropped, " + writes.failures() + " failed "
                                        + "batches", render.bad())));
                    }
                    source.sendMessage(render.row("sessions", Component.text(
                            plugin.sessions().onlineCount() + " live, "
                                    + plugin.sessions().all().size() + " tracked", render.value())));
                    source.sendMessage(render.row("population", Component.text(
                            plugin.sampler().samplesTaken() + " samples, peak "
                                    + plugin.sampler().peak(), render.value())));
                    source.sendMessage(render.row("geolocation", Component.text(
                            geo.providers().isEmpty() ? "no providers available"
                                    : String.join(" → ", geo.providers()) + "  ·  " + geo.hits()
                                            + " hits, " + geo.misses() + " lookups, "
                                            + geo.failures() + " failures",
                            geo.providers().isEmpty() ? render.warn() : render.value())));
                    source.sendMessage(render.row("bridge", Component.text(
                            bridge.backends() + " backend(s) with the companion · "
                                    + bridge.answered() + " answered, " + bridge.timedOut()
                                    + " timed out", render.value())));
                    source.sendMessage(render.row("placeholders", Component.text(
                            placeholders.enabled()
                                    ? placeholders.requested() + " requested, "
                                            + placeholders.stored() + " stored"
                                    : "disabled", render.value())));
                    source.sendMessage(render.row("webhooks", Component.text(
                            webhooks.enabled()
                                    ? webhooks.delivered() + " delivered, " + webhooks.dropped()
                                            + " dropped, " + webhooks.queued() + " queued"
                                    : "disabled", render.value())));

                    plugin.maintenance().tableSizes().thenAccept(sizes -> {
                        source.sendMessage(Component.text("  rows", render.label(),
                                TextDecoration.BOLD));
                        sizes.forEach((table, count) -> {
                            if (count > 0) {
                                source.sendMessage(Component.text(
                                        "    " + table.substring(3) + "  " + count, render.value()));
                            }
                        });
                    });
                    return 1;
                });
    }

    private LiteralArgumentBuilder<CommandSource> export() {
        return literal("export")
                .requires(permission(PERM_EXPORT))
                .then(arg("what", StringArgumentType.word())
                        .suggests(suggest(ExportService.targets().toArray(String[]::new)))
                        .executes(context -> doExport(context, "csv", "30d"))
                        .then(arg("format", StringArgumentType.word())
                                .suggests(suggest("csv", "json"))
                                .executes(context -> doExport(context,
                                        StringArgumentType.getString(context, "format"), "30d"))
                                .then(arg("since", StringArgumentType.word())
                                        .suggests(suggest("24h", "7d", "30d", "365d", "0"))
                                        .executes(context -> doExport(context,
                                                StringArgumentType.getString(context, "format"),
                                                StringArgumentType.getString(context,
                                                        "since"))))));
    }

    private int doExport(CommandContext<CommandSource> context, String format, String since) {
        CommandSource source = context.getSource();
        String what = StringArgumentType.getString(context, "what");
        if (!ExportService.isTarget(what)) {
            send(source, messages().usage,
                    "usage", "/joinstats export <" + String.join("|", ExportService.targets())
                            + "> [csv|json] [since]");
            return 1;
        }
        Duration age = Durations.parse(since, Duration.ofDays(30));
        long from = age.isZero() ? 0L : System.currentTimeMillis() - age.toMillis();

        send(source, messages().working);
        plugin.exports().export(what, format, from).thenAccept(result ->
                        send(source, messages().exportDone,
                                "rows", result.rows(),
                                "file", result.file().getFileName().toString()))
                .exceptionally(error -> {
                    send(source, messages().exportFailed, "error", rootMessage(error));
                    return null;
                });
        return 1;
    }

    private LiteralArgumentBuilder<CommandSource> prune() {
        return literal("prune")
                .requires(permission(PERM_ADMIN))
                .executes(context -> {
                    CommandSource source = context.getSource();
                    if (!plugin.config().retention.enabled) {
                        send(source, messages().featureDisabled, "feature", "Retention");
                        return 1;
                    }
                    send(source, messages().working);
                    plugin.retention().run().thenAccept(deleted -> {
                        Rendering render = rendering();
                        source.sendMessage(render.header("Retention"));
                        if (deleted.isEmpty()) {
                            source.sendMessage(render.note("Nothing was old enough to remove."));
                            return;
                        }
                        deleted.forEach((table, rows) -> source.sendMessage(
                                render.row(table, Component.text(rows + " rows removed",
                                        render.value()))));
                    }).exceptionally(reportTo(source, "run retention"));
                    return 1;
                });
    }

    private LiteralArgumentBuilder<CommandSource> forget() {
        return literal("forget")
                .requires(permission(PERM_ADMIN))
                .then(playerArgument()
                        .executes(context -> {
                            CommandSource source = context.getSource();
                            if (!plugin.config().privacy.allowForget) {
                                send(source, messages().featureDisabled, "feature", "Erasure");
                                return 1;
                            }
                            send(source, messages().forgetConfirm,
                                    "player", argument(context, "player"));
                            return 1;
                        })
                        .then(literal("confirm").executes(context -> {
                            CommandSource source = context.getSource();
                            if (!plugin.config().privacy.allowForget) {
                                send(source, messages().featureDisabled, "feature", "Erasure");
                                return 1;
                            }
                            String query = argument(context, "player");
                            withProfile(source, query, profile -> {
                                String username = profile.username();
                                plugin.maintenance().forget(profile.uuid()).thenAccept(rows -> {
                                    plugin.alerts().forget(profile.uuid());
                                    plugin.logger().info("{} erased every record of {} ({} rows).",
                                            sourceName(source), username, rows);
                                    send(source, messages().forgetDone,
                                            "rows", rows, "player", username);
                                }).exceptionally(reportTo(source, "erase that account"));
                            });
                            return 1;
                        })));
    }

    private LiteralArgumentBuilder<CommandSource> reload() {
        return literal("reload")
                .requires(permission(PERM_ADMIN))
                .executes(context -> {
                    CommandSource source = context.getSource();
                    try {
                        long millis = plugin.reload();
                        send(source, messages().reloaded, "ms", millis);
                    } catch (Exception e) {
                        plugin.logger().error("Reload failed.", e);
                        send(source, messages().reloadFailed, "error", rootMessage(e));
                    }
                    return 1;
                });
    }

    private int help(CommandSource source) {
        Rendering render = rendering();
        source.sendMessage(render.header("JoinStatistics"));
        String[][] entries = {
                {"lookup <player>", "everything known about an account", PERM_LOOKUP},
                {"sessions <player>", "session history, with merged rejoins", PERM_SESSIONS},
                {"activity [player]", "when they (or the network) are online", PERM_LOOKUP},
                {"alts <player>", "accounts sharing an address", PERM_ALTS},
                {"ip <address>", "what is known about an address", PERM_ADDRESS},
                {"papi <player>", "backend PlaceholderAPI values", PERM_LOOKUP},
                {"top [metric]", "leaderboards", PERM_LOOKUP},
                {"online", "who is on right now", PERM_LOOKUP},
                {"counts [window]", "player count over time", PERM_LOOKUP},
                {"servers", "playtime per backend", PERM_LOOKUP},
                {"overview", "network totals", PERM_LOOKUP},
                {"events / chat / commands", "the raw logs", PERM_LOOKUP},
                {"alerts", "flagged patterns", PERM_ALERTS},
                {"search <query>", "find an account by name", PERM_LOOKUP},
                {"note / tag", "annotate an account", PERM_NOTES},
                {"export <what>", "dump a table to CSV or JSON", PERM_EXPORT},
                {"status", "how the plugin itself is doing", PERM_ADMIN},
                {"prune", "run retention now", PERM_ADMIN},
                {"forget <player>", "erase an account permanently", PERM_ADMIN},
                {"reload", "re-read the configuration", PERM_ADMIN},
        };
        for (String[] entry : entries) {
            if (!source.hasPermission(entry[2])) {
                continue;
            }
            source.sendMessage(Component.text()
                    .append(Component.text("  /joinstats " + entry[0], render.accent())
                            .clickEvent(ClickEvent.suggestCommand("/joinstats "
                                    + entry[0].split(" ")[0] + " ")))
                    .append(Component.text("  " + entry[1], render.label()))
                    .build());
        }
        if (source.hasPermission(PERM_SELF) && plugin.config().privacy.selfServiceLookup) {
            source.sendMessage(Component.text("  /joinstats me", render.accent())
                    .append(Component.text("  what this plugin has recorded about you",
                            render.label())));
        }
        return 1;
    }

    // ------------------------------------------------------------------ plumbing

    private static LiteralArgumentBuilder<CommandSource> literal(String name) {
        return BrigadierCommand.literalArgumentBuilder(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSource, T> arg(
            String name, com.mojang.brigadier.arguments.ArgumentType<T> type) {
        return BrigadierCommand.requiredArgumentBuilder(name, type);
    }

    private RequiredArgumentBuilder<CommandSource, String> playerArgument() {
        return arg("player", StringArgumentType.word()).suggests(playerSuggestions());
    }

    private static RequiredArgumentBuilder<CommandSource, Integer> pageArgument() {
        return arg("page", IntegerArgumentType.integer(1));
    }

    private static String argument(CommandContext<CommandSource> context, String name) {
        return StringArgumentType.getString(context, name);
    }

    private static java.util.function.Predicate<CommandSource> permission(String node) {
        return source -> source.hasPermission(node);
    }

    /**
     * Looks a player up, replying with "not found" when there is no such account.
     *
     * <p>Every subcommand that takes a player name needs exactly this, and threading the
     * not-found reply through each of them by hand is how one of them ends up silently doing
     * nothing instead.
     */
    private void withProfile(CommandSource source, String query,
                             Consumer<PlayerProfile> then) {
        plugin.players().resolve(query).thenAccept(found -> {
            if (found.isEmpty()) {
                send(source, messages().playerNotFound, "query", query);
                return;
            }
            then.accept(found.get());
        }).exceptionally(reportTo(source, "look up " + query));
    }

    /** Reports a failed query to the person who asked for it, and logs the detail. */
    private java.util.function.Function<Throwable, Void> reportTo(CommandSource source,
                                                                  String what) {
        return error -> {
            plugin.logger().warn("Command failed while trying to {}.", what, error);
            source.sendMessage(messages().render(
                    "<prefix><bad>Could not <what>: <error></bad>",
                    "what", what, "error", rootMessage(error)));
            return null;
        };
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private void send(CommandSource source, String template, Object... placeholders) {
        source.sendMessage(messages().render(template, placeholders));
    }

    private Messages messages() {
        return plugin.messages();
    }

    private Rendering rendering() {
        return new Rendering(plugin.messages(), plugin.zone());
    }

    private int pageSize() {
        return Math.max(1, plugin.config().commands.pageSize);
    }

    private static String sourceName(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : "console";
    }

    private static UUID sourceUuid(CommandSource source) {
        return source instanceof Player player ? player.getUniqueId() : null;
    }

    private static String metricNames() {
        StringBuilder names = new StringBuilder();
        for (PlayerDao.Metric metric : PlayerDao.Metric.values()) {
            if (names.length() > 0) {
                names.append('|');
            }
            names.append(metric.name().toLowerCase(Locale.ROOT));
        }
        return names.toString();
    }

    private static String bytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double scaled = value;
        int unit = -1;
        while (scaled >= 1024 && unit < units.length - 1) {
            scaled /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", scaled, units[unit]);
    }

    // ------------------------------------------------------------------ suggestions

    /**
     * Completes online players first, then names from the database.
     *
     * <p>Online players are what staff want nine times out of ten, and offering them without a
     * round trip keeps completion responsive on a network with a large history.
     */
    private SuggestionProvider<CommandSource> playerSuggestions() {
        return (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            List<String> offered = new ArrayList<>();
            for (Player player : plugin.proxy().getAllPlayers()) {
                if (player.getUsername().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(player.getUsername());
                    offered.add(player.getUsername().toLowerCase(Locale.ROOT));
                }
            }
            if (remaining.length() < 2) {
                return builder.buildFuture();
            }
            int limit = Math.max(1, plugin.config().commands.completionLimit);
            return plugin.players().completeNames(remaining, limit).thenApply(names -> {
                for (String name : names) {
                    if (!offered.contains(name.toLowerCase(Locale.ROOT))) {
                        builder.suggest(name);
                    }
                }
                return builder.build();
            }).exceptionally(error -> builder.build());
        };
    }

    private SuggestionProvider<CommandSource> placeholderSuggestions() {
        return (context, builder) -> {
            for (String placeholder : plugin.placeholders().placeholdersFor(null)) {
                builder.suggest(placeholder);
            }
            return plugin.placeholderDao().knownPlaceholders().thenApply(known -> {
                known.forEach(builder::suggest);
                return builder.build();
            }).exceptionally(error -> builder.build());
        };
    }

    private SuggestionProvider<CommandSource> tagSuggestions() {
        return (context, builder) -> plugin.annotations().knownTags().thenApply(tags -> {
            tags.forEach(builder::suggest);
            return builder.build();
        }).exceptionally(error -> builder.build());
    }

    private static SuggestionProvider<CommandSource> metricSuggestions() {
        return (context, builder) -> {
            for (PlayerDao.Metric metric : PlayerDao.Metric.values()) {
                builder.suggest(metric.name().toLowerCase(Locale.ROOT));
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSource> suggest(String... options) {
        return (context, builder) -> {
            for (String option : options) {
                builder.suggest(option);
            }
            return builder.buildFuture();
        };
    }
}
