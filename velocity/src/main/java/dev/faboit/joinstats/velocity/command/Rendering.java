package dev.faboit.joinstats.velocity.command;

import dev.faboit.joinstats.velocity.analytics.ProfileService;
import dev.faboit.joinstats.velocity.config.Messages;
import dev.faboit.joinstats.velocity.storage.model.AddressRecord;
import dev.faboit.joinstats.velocity.storage.model.PlayerProfile;
import dev.faboit.joinstats.velocity.storage.model.Records;
import dev.faboit.joinstats.velocity.storage.model.SessionRecord;
import dev.faboit.joinstats.velocity.util.Durations;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Turns rows into something readable in a chat window.
 *
 * <p>Chat is a narrow, monospaced-ish, unscrollable surface, so the priorities here are: the
 * important number first, units always spelled out, and anything long moved into a hover rather
 * than wrapped across three lines. Colours come from {@code messages.conf} so a network can match
 * the plugin to the rest of its theme.
 */
final class Rendering {

    /** Shading ramp for the activity heatmap and the population sparkline. */
    private static final char[] RAMP = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter TIME_ONLY =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final Messages messages;
    private final ZoneId zone;

    Rendering(Messages messages, ZoneId zone) {
        this.messages = messages;
        this.zone = zone;
    }

    // ------------------------------------------------------------------ palette

    private TextColor colour(String hex) {
        TextColor parsed = TextColor.fromCSSHexString(hex.startsWith("#") ? hex : "#" + hex);
        return parsed == null ? NamedTextColor.WHITE : parsed;
    }

    TextColor accent() {
        return colour(messages.accent);
    }

    TextColor accentAlt() {
        return colour(messages.accentAlt);
    }

    TextColor label() {
        return colour(messages.label);
    }

    TextColor value() {
        return colour(messages.value);
    }

    TextColor good() {
        return colour(messages.good);
    }

    TextColor warn() {
        return colour(messages.warn);
    }

    TextColor bad() {
        return colour(messages.bad);
    }

    // ------------------------------------------------------------------ structure

    Component header(String title) {
        return Component.text()
                .append(Component.text("──────── ", label()))
                .append(Component.text(title, accent(), TextDecoration.BOLD))
                .append(Component.text(" ────────", label()))
                .build();
    }

    Component header(String title, int page, int pages) {
        if (pages <= 1) {
            return header(title);
        }
        return Component.text()
                .append(Component.text("──── ", label()))
                .append(Component.text(title, accent(), TextDecoration.BOLD))
                .append(Component.text(" (" + page + "/" + pages + ")", label()))
                .append(Component.text(" ────", label()))
                .build();
    }

    /** A {@code label: value} line. */
    Component row(String key, Component body) {
        return Component.text()
                .append(Component.text("  " + key + ": ", label()))
                .append(body)
                .build();
    }

    Component row(String key, String body) {
        return row(key, Component.text(body == null || body.isBlank() ? "—" : body, value()));
    }

    Component note(String text) {
        return Component.text("  " + text, label(), TextDecoration.ITALIC);
    }

    /** Pagination controls that re-run the command for the previous and next page. */
    Component pageControls(String commandPrefix, int page, int pages) {
        if (pages <= 1) {
            return Component.empty();
        }
        TextComponent.Builder builder = Component.text().append(Component.text("  ", label()));
        if (page > 1) {
            builder.append(Component.text("« prev", accent())
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " " + (page - 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Page " + (page - 1)))));
        } else {
            builder.append(Component.text("« prev", label()));
        }
        builder.append(Component.text("   " + page + " / " + pages + "   ", label()));
        if (page < pages) {
            builder.append(Component.text("next »", accent())
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " " + (page + 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Page " + (page + 1)))));
        } else {
            builder.append(Component.text("next »", label()));
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ values

    String timestamp(long epochMillis) {
        if (epochMillis <= 0) {
            return "never";
        }
        return DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }

    String clock(long epochMillis) {
        return TIME_ONLY.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }

    /** An absolute timestamp with the relative form in a hover, since both are usually wanted. */
    Component when(long epochMillis) {
        if (epochMillis <= 0) {
            return Component.text("never", label());
        }
        return Component.text(Durations.ago(epochMillis, System.currentTimeMillis()), value())
                .hoverEvent(HoverEvent.showText(Component.text(timestamp(epochMillis), value())));
    }

    Component duration(long millis) {
        return Component.text(Durations.format(millis), value());
    }

    /** A clickable name that runs the lookup for that account. */
    Component playerLink(String username) {
        return Component.text(username, accent())
                .clickEvent(ClickEvent.runCommand("/joinstats lookup " + username))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Look up " + username, label())));
    }

    // ------------------------------------------------------------------ profiles

    /** The main {@code /joinstats lookup} body. */
    List<Component> profile(Records.FullProfile full, boolean maySeeAddresses,
                            java.util.function.BiFunction<String, Boolean, String> addressFormatter,
                            boolean online, long liveSessionMillis) {
        PlayerProfile profile = full.profile();
        List<Component> lines = new ArrayList<>();

        lines.add(Component.text()
                .append(Component.text("  " + profile.username(), accent(), TextDecoration.BOLD))
                .append(Component.text(online ? "  ● online" : "  ○ offline",
                        online ? good() : label()))
                .build());
        lines.add(Component.text("  " + profile.uuid(), label())
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy", label())))
                .clickEvent(ClickEvent.copyToClipboard(profile.uuid().toString())));

        if (!full.tags().isEmpty()) {
            TextComponent.Builder tags = Component.text().append(Component.text("  tags: ", label()));
            for (int i = 0; i < full.tags().size(); i++) {
                if (i > 0) {
                    tags.append(Component.text(", ", label()));
                }
                tags.append(Component.text(full.tags().get(i).tag(), accentAlt()));
            }
            lines.add(tags.build());
        }

        lines.add(Component.empty());
        lines.add(row("first seen", when(profile.firstSeen())));
        lines.add(row("last seen", online
                ? Component.text("now", good()) : when(profile.lastSeen())));
        lines.add(row("playtime", Component.text()
                .append(duration(profile.playtime() + (online ? liveSessionMillis : 0)))
                .append(Component.text(online && liveSessionMillis > 0
                        ? " (+" + Durations.format(liveSessionMillis) + " this session)" : "",
                        label()))
                .build()));
        lines.add(row("sessions", Component.text()
                .append(Component.text(String.valueOf(profile.sessions()), value()))
                .append(Component.text(" · avg ", label()))
                .append(duration(profile.averageSession()))
                .append(Component.text(" · longest ", label()))
                .append(duration(profile.longestSession()))
                .build()));
        lines.add(row("connections", Component.text()
                .append(Component.text(String.valueOf(profile.connections()), value()))
                .append(Component.text(
                        profile.connections() > profile.sessions() && profile.sessions() > 0
                                ? "  (" + (profile.connections() - profile.sessions())
                                        + " rejoins merged)" : "",
                        label()))
                .build()));

        if (profile.idleTime() > 0) {
            lines.add(row("idle", Component.text()
                    .append(duration(profile.idleTime()))
                    .append(Component.text(String.format(Locale.ROOT, "  (%.0f%% of playtime)",
                            profile.idleRatio() * 100), label()))
                    .build()));
        }

        lines.add(row("activity", Component.text()
                .append(Component.text(profile.chatMessages() + " chat", value()))
                .append(Component.text(" · ", label()))
                .append(Component.text(profile.commands() + " commands", value()))
                .append(Component.text(" · ", label()))
                .append(Component.text(profile.serverSwitches() + " switches", value()))
                .append(Component.text(" · ", label()))
                .append(Component.text(profile.kicks() + " kicks",
                        profile.kicks() > 0 ? warn() : value()))
                .build()));

        if (profile.averagePing() >= 0) {
            lines.add(row("latency", Component.text(
                    profile.averagePing() + "ms average, " + profile.pingBest() + "–"
                            + profile.pingWorst() + "ms range", value())));
        }

        lines.add(Component.empty());
        lines.add(row("client", Component.text()
                .append(Component.text(orDash(profile.lastVersion()), value()))
                .append(Component.text(profile.lastBrand() == null ? ""
                        : "  (" + profile.lastBrand() + ")", label()))
                .append(Component.text(profile.onlineMode() ? "  · premium" : "  · offline mode",
                        profile.onlineMode() ? good() : warn()))
                .build()));
        if (profile.lastLocale() != null) {
            lines.add(row("locale", profile.lastLocale()));
        }

        lines.add(row("address", Component.text(
                addressFormatter.apply(profile.lastAddress(), maySeeAddresses), value())));
        AddressRecord latestGeo = full.geo().isEmpty() ? null : full.geo().get(0);
        if (latestGeo != null) {
            lines.add(row("location", Component.text()
                    .append(Component.text(latestGeo.describeLocation(), value()))
                    .append(Component.text(latestGeo.anonymised()
                            ? "  ⚠ " + latestGeo.networkKind() : "", warn()))
                    .build()));
            if (latestGeo.asName() != null || latestGeo.isp() != null) {
                lines.add(row("network", Component.text(
                        latestGeo.asName() != null ? latestGeo.asName() : latestGeo.isp(), value())
                        .hoverEvent(HoverEvent.showText(Component.text(
                                (latestGeo.asn() == null ? "" : "AS" + latestGeo.asn() + "  ")
                                        + orDash(latestGeo.hostname()), label())))));
            }
        }

        if (!full.names().isEmpty() && full.names().size() > 1) {
            lines.add(row("known as", Component.text(
                    full.names().stream().map(Records.NameHistoryEntry::username)
                            .reduce((a, b) -> a + ", " + b).orElse(""), value())));
        }

        if (!full.servers().isEmpty()) {
            lines.add(Component.empty());
            lines.add(Component.text("  servers", label(), TextDecoration.BOLD));
            int shown = 0;
            for (Records.ServerPlaytime server : full.servers()) {
                if (shown++ >= 6) {
                    lines.add(note("… and " + (full.servers().size() - 6) + " more"));
                    break;
                }
                lines.add(Component.text()
                        .append(Component.text("    " + server.server() + " ", value()))
                        .append(Component.text(Durations.format(server.playtime()), accent()))
                        .append(Component.text("  " + server.joins() + " visits", label()))
                        .build());
            }
        }

        ProfileService.peakActivity(full.activity()).ifPresent(peak -> {
            lines.add(Component.empty());
            lines.add(row("plays most", Component.text(
                    peak.dayName() + "s, around " + String.format(Locale.ROOT, "%02d:00", peak.hour()),
                    value())));
        });

        if (!full.alts().isEmpty()) {
            lines.add(Component.empty());
            TextComponent.Builder alts = Component.text()
                    .append(Component.text("  shares an address with: ", warn()));
            for (int i = 0; i < Math.min(5, full.alts().size()); i++) {
                if (i > 0) {
                    alts.append(Component.text(", ", label()));
                }
                alts.append(playerLink(full.alts().get(i).username()));
            }
            if (full.alts().size() > 5) {
                alts.append(Component.text("  +" + (full.alts().size() - 5) + " more", label()));
            }
            lines.add(alts.build());
        }

        if (!full.notes().isEmpty()) {
            lines.add(Component.empty());
            lines.add(Component.text("  notes", label(), TextDecoration.BOLD));
            for (Records.Note entry : full.notes().subList(0, Math.min(3, full.notes().size()))) {
                lines.add(Component.text()
                        .append(Component.text("    " + entry.note(), value()))
                        .append(Component.text("  — " + entry.author(), label()))
                        .build());
            }
        }

        return lines;
    }

    /** One line of {@code /joinstats sessions}. */
    Component sessionLine(SessionRecord session) {
        TextComponent.Builder builder = Component.text()
                .append(Component.text("  " + timestamp(session.startedAt()) + "  ", label()))
                .append(Component.text(Durations.format(session.duration()), accent()));

        if (session.open()) {
            builder.append(Component.text("  ● live", good()));
        } else if (session.crashed()) {
            builder.append(Component.text("  ⚠ recovered", warn()));
        }
        if (session.wasResumed()) {
            builder.append(Component.text("  ×" + session.connections(), accentAlt())
                    .hoverEvent(HoverEvent.showText(Component.text(
                            session.connections() + " connections merged into this session, "
                                    + Durations.format(session.gapTime()) + " offline",
                            label()))));
        }
        if (session.lastServer() != null) {
            builder.append(Component.text("  " + session.lastServer(), value()));
        }

        List<Component> tooltip = new ArrayList<>();
        tooltip.add(Component.text("session #" + session.id(), accent()));
        tooltip.add(Component.text("started  " + timestamp(session.startedAt()), label()));
        tooltip.add(Component.text("ended    "
                + (session.open() ? "still open" : timestamp(session.endedAt())), label()));
        tooltip.add(Component.text("servers  " + session.serversSeen(), label()));
        tooltip.add(Component.text("chat     " + session.chatMessages()
                + "   commands " + session.commands(), label()));
        if (session.averagePing() >= 0) {
            tooltip.add(Component.text("ping     " + session.averagePing() + "ms", label()));
        }
        if (session.versionName() != null) {
            tooltip.add(Component.text("client   " + session.versionName()
                    + (session.brand() == null ? "" : " (" + session.brand() + ")"), label()));
        }
        if (session.virtualHost() != null) {
            tooltip.add(Component.text("via      " + session.virtualHost(), label()));
        }
        if (session.quitReason() != null) {
            tooltip.add(Component.text("left     " + session.quitReason(), label()));
        }
        return builder.build().hoverEvent(HoverEvent.showText(
                Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), tooltip)));
    }

    // ------------------------------------------------------------------ charts

    /**
     * A compact bar chart of a population series.
     *
     * <p>Scaled to the maximum in the window rather than to an absolute ceiling, so the shape of
     * a quiet Tuesday is as readable as that of a launch day.
     */
    Component sparkline(List<Records.PopulationSample> samples, int width) {
        if (samples.isEmpty()) {
            return Component.text("  no samples in this window", label());
        }
        List<Integer> buckets = downsample(samples, width);
        int peak = 0;
        for (int sample : buckets) {
            peak = Math.max(peak, sample);
        }
        // The divisor is floored at one so an all-zero window does not divide by zero. The
        // reported peak is the real maximum, which is not the same number: reusing the divisor
        // made an empty proxy claim a peak of one player.
        int scale = Math.max(1, peak);
        StringBuilder chart = new StringBuilder(buckets.size());
        for (int sample : buckets) {
            chart.append(RAMP[Math.min(RAMP.length - 1, sample * (RAMP.length - 1) / scale)]);
        }
        return Component.text()
                .append(Component.text("  " + chart, accent()))
                .append(Component.text("  peak " + peak, label()))
                .build();
    }

    /** Averages a series down to a fixed number of columns. */
    private static List<Integer> downsample(List<Records.PopulationSample> samples, int width) {
        List<Integer> out = new ArrayList<>(width);
        if (samples.size() <= width) {
            for (Records.PopulationSample sample : samples) {
                out.add(sample.total());
            }
            return out;
        }
        double perBucket = (double) samples.size() / width;
        for (int i = 0; i < width; i++) {
            int from = (int) Math.floor(i * perBucket);
            int to = (int) Math.floor((i + 1) * perBucket);
            long sum = 0;
            int count = 0;
            for (int j = from; j < Math.min(to, samples.size()); j++) {
                sum += samples.get(j).total();
                count++;
            }
            out.add(count == 0 ? 0 : (int) (sum / count));
        }
        return out;
    }

    /**
     * A seven-by-twenty-four heatmap of when someone is online.
     *
     * <p>One row per weekday, one column per hour, shaded against the busiest cell. This is the
     * one view that reliably answers "when will this player actually be around", which is the
     * question staff have when they want to talk to someone.
     */
    List<Component> heatmap(List<Records.ActivityCell> cells) {
        long[][] grid = new long[8][24];
        long max = 0;
        for (Records.ActivityCell cell : cells) {
            if (cell.dayOfWeek() < 1 || cell.dayOfWeek() > 7 || cell.hour() < 0
                    || cell.hour() > 23) {
                continue;
            }
            grid[cell.dayOfWeek()][cell.hour()] += cell.playtime();
            max = Math.max(max, grid[cell.dayOfWeek()][cell.hour()]);
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("      00      06      12      18    ", label()));
        if (max == 0) {
            lines.add(note("no activity recorded yet"));
            return lines;
        }
        for (int day = 1; day <= 7; day++) {
            String name = java.time.DayOfWeek.of(day)
                    .getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
            TextComponent.Builder row = Component.text()
                    .append(Component.text("  " + name + " ", label()));
            for (int hour = 0; hour < 24; hour++) {
                long amount = grid[day][hour];
                if (amount == 0) {
                    row.append(Component.text("·", label()));
                    continue;
                }
                int level = (int) Math.min(RAMP.length - 1, amount * (RAMP.length - 1) / max);
                row.append(Component.text(String.valueOf(RAMP[level]),
                                level >= 5 ? accentAlt() : accent())
                        .hoverEvent(HoverEvent.showText(Component.text(
                                name + " " + String.format(Locale.ROOT, "%02d:00", hour) + " — "
                                        + Durations.format(amount), value()))));
            }
            lines.add(row.build());
        }
        lines.add(Component.text("      busiest cell: " + Durations.format(max), label()));
        return lines;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
