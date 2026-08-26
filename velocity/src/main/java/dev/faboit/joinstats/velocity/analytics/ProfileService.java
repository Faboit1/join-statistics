package dev.faboit.joinstats.velocity.analytics;

import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.storage.dao.AddressDao;
import dev.faboit.joinstats.velocity.storage.dao.AnnotationDao;
import dev.faboit.joinstats.velocity.storage.dao.PlaceholderDao;
import dev.faboit.joinstats.velocity.storage.dao.PlayerDao;
import dev.faboit.joinstats.velocity.storage.dao.SessionDao;
import dev.faboit.joinstats.velocity.storage.model.PlayerProfile;
import dev.faboit.joinstats.velocity.storage.model.Records;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Assembles the complete picture of one account from the tables that hold pieces of it.
 *
 * <p>Every part is fetched concurrently and joined once they all arrive. Done sequentially, a
 * lookup would be a dozen round trips deep; done this way it costs about as long as the slowest
 * single query.
 */
public final class ProfileService {

    private final PlayerDao players;
    private final SessionDao sessions;
    private final AddressDao addresses;
    private final PlaceholderDao placeholders;
    private final AnnotationDao annotations;
    private final Supplier<PluginConfig> config;

    public ProfileService(PlayerDao players, SessionDao sessions, AddressDao addresses,
                          PlaceholderDao placeholders, AnnotationDao annotations,
                          Supplier<PluginConfig> config) {
        this.players = players;
        this.sessions = sessions;
        this.addresses = addresses;
        this.placeholders = placeholders;
        this.annotations = annotations;
        this.config = config;
    }

    /** Resolves a name or UUID to a profile. */
    public CompletableFuture<Optional<PlayerProfile>> resolve(String query) {
        return players.resolve(query);
    }

    /**
     * Everything known about an account.
     *
     * @param sessionLimit how many recent sessions to include
     * @param dailyDays    how many days of the activity calendar to include
     */
    public CompletableFuture<Optional<Records.FullProfile>> full(String query, int sessionLimit,
                                                                 int dailyDays) {
        return players.resolve(query).thenCompose(found -> found
                .map(profile -> full(profile, sessionLimit, dailyDays).thenApply(Optional::of))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())));
    }

    /** Same, for a profile already in hand. */
    public CompletableFuture<Records.FullProfile> full(PlayerProfile profile, int sessionLimit,
                                                       int dailyDays) {
        UUID uuid = profile.uuid();
        PluginConfig.Alerts alertSettings = config.get().alerts;
        long altSince = System.currentTimeMillis() - alertSettings.altWindow().toMillis();

        CompletableFuture<List<Records.NameHistoryEntry>> names = players.nameHistory(uuid);
        CompletableFuture<List<Records.PlayerAddress>> addressList = addresses.addressesOf(uuid);
        CompletableFuture<List<dev.faboit.joinstats.velocity.storage.model.AddressRecord>> geo =
                addresses.geoFor(uuid);
        CompletableFuture<List<Records.ServerPlaytime>> servers = sessions.serversOf(uuid);
        CompletableFuture<List<dev.faboit.joinstats.velocity.storage.model.SessionRecord>> recent =
                sessions.recent(uuid, sessionLimit, 0);
        CompletableFuture<List<Records.PlaceholderValue>> placeholderValues =
                placeholders.current(uuid);
        CompletableFuture<List<Records.ActivityCell>> activity = sessions.hourlyActivity(uuid);
        CompletableFuture<List<Records.DailyActivity>> daily = sessions.dailyActivity(uuid, dailyDays);
        CompletableFuture<List<Records.AltAccount>> alts =
                addresses.findAlts(uuid, altSince, alertSettings.altMatchSubnet, 25);
        CompletableFuture<List<Records.Alert>> alertList = annotations.alertsFor(uuid, 15);
        CompletableFuture<List<Records.Note>> notes = annotations.notesFor(uuid, 25);
        CompletableFuture<List<Records.Tag>> tags = annotations.tagsFor(uuid);

        return CompletableFuture.allOf(names, addressList, geo, servers, recent, placeholderValues,
                        activity, daily, alts, alertList, notes, tags)
                .thenApply(ignored -> new Records.FullProfile(
                        profile,
                        names.join(),
                        addressList.join(),
                        geo.join(),
                        servers.join(),
                        recent.join(),
                        placeholderValues.join(),
                        activity.join(),
                        daily.join(),
                        alts.join(),
                        alertList.join(),
                        notes.join(),
                        tags.join()));
    }

    /**
     * Summarises when an account tends to play.
     *
     * @return the busiest hour of the day and day of the week, or empty when there is no data yet
     */
    public static Optional<PeakActivity> peakActivity(List<Records.ActivityCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return Optional.empty();
        }
        long[] byHour = new long[24];
        long[] byDay = new long[8];
        long total = 0;
        for (Records.ActivityCell cell : cells) {
            if (cell.hour() >= 0 && cell.hour() < 24) {
                byHour[cell.hour()] += cell.playtime();
            }
            if (cell.dayOfWeek() >= 1 && cell.dayOfWeek() <= 7) {
                byDay[cell.dayOfWeek()] += cell.playtime();
            }
            total += cell.playtime();
        }
        if (total <= 0) {
            return Optional.empty();
        }

        int bestHour = 0;
        for (int hour = 1; hour < 24; hour++) {
            if (byHour[hour] > byHour[bestHour]) {
                bestHour = hour;
            }
        }
        int bestDay = 1;
        for (int day = 2; day <= 7; day++) {
            if (byDay[day] > byDay[bestDay]) {
                bestDay = day;
            }
        }
        return Optional.of(new PeakActivity(bestHour, byHour[bestHour], bestDay, byDay[bestDay],
                total));
    }

    /** When an account is most often online. */
    public record PeakActivity(int hour, long hourPlaytime, int dayOfWeek, long dayPlaytime,
                               long totalPlaytime) {

        public String dayName() {
            return java.time.DayOfWeek.of(Math.max(1, Math.min(7, dayOfWeek)))
                    .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
        }

        /** The share of all playtime that falls in the busiest hour, 0..1. */
        public double hourShare() {
            return totalPlaytime <= 0 ? 0 : (double) hourPlaytime / (double) totalPlaytime;
        }
    }
}
