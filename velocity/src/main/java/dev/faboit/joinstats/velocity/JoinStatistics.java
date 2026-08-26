package dev.faboit.joinstats.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.faboit.joinstats.velocity.analytics.AlertService;
import dev.faboit.joinstats.velocity.analytics.ProfileService;
import dev.faboit.joinstats.velocity.analytics.RetentionService;
import dev.faboit.joinstats.velocity.bridge.BridgeService;
import dev.faboit.joinstats.velocity.bridge.PlaceholderService;
import dev.faboit.joinstats.velocity.command.JoinStatsCommand;
import dev.faboit.joinstats.velocity.config.ConfigManager;
import dev.faboit.joinstats.velocity.config.Messages;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.export.ExportService;
import dev.faboit.joinstats.velocity.geo.GeoService;
import dev.faboit.joinstats.velocity.notify.WebhookService;
import dev.faboit.joinstats.velocity.storage.Database;
import dev.faboit.joinstats.velocity.storage.WriteQueue;
import dev.faboit.joinstats.velocity.storage.dao.AddressDao;
import dev.faboit.joinstats.velocity.storage.dao.AnnotationDao;
import dev.faboit.joinstats.velocity.storage.dao.EventDao;
import dev.faboit.joinstats.velocity.storage.dao.MaintenanceDao;
import dev.faboit.joinstats.velocity.storage.dao.PlaceholderDao;
import dev.faboit.joinstats.velocity.storage.dao.PlayerDao;
import dev.faboit.joinstats.velocity.storage.dao.PopulationDao;
import dev.faboit.joinstats.velocity.storage.dao.SessionDao;
import dev.faboit.joinstats.velocity.tracking.ConnectionListener;
import dev.faboit.joinstats.velocity.tracking.PopulationSampler;
import dev.faboit.joinstats.velocity.tracking.SessionManager;
import dev.faboit.joinstats.velocity.util.Durations;
import dev.faboit.joinstats.velocity.util.Privacy;
import dev.faboit.joinstats.velocity.web.ApiServer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Proxy-side player analytics for Velocity.
 *
 * <p>This class does the wiring and nothing else: build the storage, build the services that read
 * and write it, register the listeners, and put the periodic work on the scheduler. Everything
 * interesting lives in the packages below it.
 *
 * <p>The shape of the whole plugin is: events are observed on Velocity's threads and turned into
 * queued writes, which one background thread applies in batches; reads run on a small pool; and
 * anything that could block — a geolocation lookup, a webhook, a placeholder round trip to a
 * backend — happens on its own executor, off the path of the player who triggered it.
 */
@Plugin(
        id = BuildConstants.ID,
        name = BuildConstants.NAME,
        version = BuildConstants.VERSION,
        description = "Comprehensive player statistics, profiling and session tracking for "
                + "Velocity networks.",
        url = "https://github.com/faboit1/join-statistics",
        authors = {"faboit1"}
)
public final class JoinStatistics {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final ConfigManager configManager;
    private final List<ScheduledTask> tasks = new ArrayList<>();

    private Database database;
    private WriteQueue writes;
    private PlayerDao players;
    private SessionDao sessionDao;
    private AddressDao addresses;
    private EventDao events;
    private PopulationDao population;
    private PlaceholderDao placeholderDao;
    private AnnotationDao annotations;
    private MaintenanceDao maintenance;

    private Privacy privacy;
    private GeoService geo;
    private WebhookService webhooks;
    private AlertService alerts;
    private SessionManager sessions;
    private ConnectionListener listener;
    private PopulationSampler sampler;
    private BridgeService bridge;
    private PlaceholderService placeholders;
    private ProfileService profiles;
    private RetentionService retention;
    private ExportService exports;
    private ApiServer api;

    private volatile boolean started;

    @Inject
    public JoinStatistics(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configManager = new ConfigManager(dataDirectory, logger);
    }

    // ------------------------------------------------------------------ lifecycle

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        long started = System.nanoTime();
        try {
            configManager.load();
        } catch (Exception e) {
            logger.error("Could not read the configuration. The plugin will not start; fix "
                    + "config.conf and restart the proxy.", e);
            return;
        }

        PluginConfig config = configManager.config();
        try {
            database = new Database(dataDirectory, config.storage, logger);
        } catch (Exception e) {
            logger.error("Could not open the statistics database. The plugin will not start.", e);
            return;
        }

        writes = new WriteQueue(database, logger, config.storage.batchSize,
                config.storage.flush().toMillis(), config.storage.maxQueuedWrites);

        players = new PlayerDao(database, writes);
        sessionDao = new SessionDao(database, writes);
        addresses = new AddressDao(database, writes);
        events = new EventDao(database, writes);
        population = new PopulationDao(database, writes);
        placeholderDao = new PlaceholderDao(database, writes);
        annotations = new AnnotationDao(database, writes);
        maintenance = new MaintenanceDao(database, writes);

        privacy = new Privacy(configManager::config);
        geo = new GeoService(dataDirectory, config.geolocation, addresses, logger);
        webhooks = new WebhookService(configManager::config, logger);
        alerts = new AlertService(configManager::config, configManager::messages, proxy,
                annotations, addresses, sessionDao, webhooks, logger);
        sessions = new SessionManager(configManager::config, players, sessionDao, addresses,
                events, this::zone, logger);

        bridge = new BridgeService(proxy, logger);
        placeholders = new PlaceholderService(proxy, bridge, placeholderDao,
                configManager::config, logger);
        listener = new ConnectionListener(this, proxy, configManager::config, privacy, sessions,
                players, addresses, events, geo, alerts, placeholders, logger);
        sampler = new PopulationSampler(proxy, population, annotations, geo,
                configManager::config, alerts::onNewPeak);
        profiles = new ProfileService(players, sessionDao, addresses, placeholderDao, annotations,
                configManager::config);
        retention = new RetentionService(maintenance, players, configManager::config, logger);
        exports = new ExportService(database, dataDirectory);

        // Sessions the previous run left open are closed at their last heartbeat, before
        // anything new is recorded on top of them.
        sessionDao.recoverOpenSessions().thenAccept(recovered -> {
            if (recovered > 0) {
                logger.info("Closed {} session(s) left open by an unclean shutdown.", recovered);
            }
        });
        annotations.counter("peak_online").thenAccept(peak -> sampler.primePeak(peak.intValue()));

        proxy.getEventManager().register(this, listener);
        proxy.getEventManager().register(this, bridge);
        bridge.register();

        JoinStatsCommand.register(this);
        startApiServer();
        scheduleTasks();

        this.started = true;
        logger.info("{} {} is tracking on {} in {}ms.", BuildConstants.NAME,
                BuildConstants.VERSION, database.file().getFileName(),
                (System.nanoTime() - started) / 1_000_000L);
        warnAboutConfiguration();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (!started) {
            return;
        }
        started = false;
        logger.info("Writing out open sessions…");

        cancelTasks();
        if (api != null) {
            api.stop();
        }
        if (bridge != null) {
            bridge.unregister();
        }
        if (sessions != null) {
            sessions.finaliseAll(System.currentTimeMillis(), false);
        }
        if (sampler != null && configManager.config().population.enabled) {
            // One final sample, so a graph does not trail off at whatever the last tick was.
            sampler.sample(System.currentTimeMillis());
        }
        if (webhooks != null) {
            webhooks.close();
        }
        if (geo != null) {
            geo.close();
        }
        if (writes != null) {
            writes.close();
        }
        if (database != null) {
            database.close();
        }
        logger.info("{} shut down cleanly.", BuildConstants.NAME);
    }

    /**
     * Re-reads the configuration and reschedules everything that depends on it.
     *
     * <p>The database connection and the write queue are deliberately left alone: changing the
     * storage settings needs a restart, and quietly swapping the database out from under an
     * in-flight batch would be a good way to lose it.
     *
     * @return how long the reload took, in milliseconds
     */
    public long reload() throws Exception {
        long started = System.nanoTime();
        configManager.load();

        cancelTasks();
        if (api != null) {
            api.stop();
            api = null;
        }
        if (geo != null) {
            geo.close();
        }
        geo = new GeoService(dataDirectory, configManager.config().geolocation, addresses, logger);
        // The listener and sampler hold the old instance, so rebuild the pieces that captured it.
        listener = new ConnectionListener(this, proxy, configManager::config, privacy, sessions,
                players, addresses, events, geo, alerts, placeholders, logger);
        sampler = new PopulationSampler(proxy, population, annotations, geo,
                configManager::config, alerts::onNewPeak);
        annotations.counter("peak_online").thenAccept(peak -> sampler.primePeak(peak.intValue()));

        proxy.getEventManager().unregisterListeners(this);
        proxy.getEventManager().register(this, listener);
        proxy.getEventManager().register(this, bridge);
        proxy.getEventManager().register(this, this);

        startApiServer();
        scheduleTasks();
        warnAboutConfiguration();
        return (System.nanoTime() - started) / 1_000_000L;
    }

    // ------------------------------------------------------------------ scheduling

    private void scheduleTasks() {
        PluginConfig config = configManager.config();

        if (config.population.enabled) {
            repeat(config.population.sampleInterval(),
                    () -> sampler.sample(System.currentTimeMillis()));
            repeat(config.population.rollupEvery(), () -> sampler.rollup(System.currentTimeMillis()));
        }

        // The sweeper has to run at least as often as the grace window, or a returning player
        // would find their session already written out.
        Duration grace = config.sessions.grace();
        Duration sweep = grace.isZero() ? Duration.ofSeconds(5)
                : Duration.ofMillis(Math.max(1000L, grace.toMillis() / 3));
        repeat(sweep, () -> sessions.sweep(System.currentTimeMillis()));

        if (!config.sessions.heartbeat().isZero()) {
            repeat(config.sessions.heartbeat(),
                    () -> sessions.heartbeatAll(System.currentTimeMillis()));
        }

        if (!config.tracking.pingSample().isZero()) {
            repeat(config.tracking.pingSample(), listener::samplePings);
        }

        if (config.placeholders.enabled) {
            repeat(config.placeholders.refresh(), placeholders::refreshAll);
            repeat(Duration.ofSeconds(5),
                    () -> bridge.expireStaleRequests(System.currentTimeMillis()));
        }

        if (config.webhooks.enabled) {
            repeat(config.webhooks.batch(), webhooks::flush);
        }

        if (config.alerts.enabled && !config.alerts.longSessionAfter().isZero()) {
            repeat(Duration.ofMinutes(5),
                    () -> alerts.checkLongSessions(System.currentTimeMillis()));
        }

        if (config.retention.enabled) {
            // Delayed so a restart loop cannot spend every startup pruning.
            delayedRepeat(Duration.ofMinutes(5), config.retention.every(), () -> retention.run());
        }

        if (config.geolocation.enabled) {
            delayedRepeat(Duration.ofMinutes(2), Duration.ofMinutes(30),
                    () -> geo.refreshStale(200));
        }

        Duration summary = config.general.summary();
        if (!summary.isZero()) {
            repeat(summary, this::logSummary);
        }
    }

    private void repeat(Duration interval, Runnable work) {
        long millis = Math.max(50L, interval.toMillis());
        tasks.add(proxy.getScheduler().buildTask(this, guard(work))
                .delay(millis, TimeUnit.MILLISECONDS)
                .repeat(millis, TimeUnit.MILLISECONDS)
                .schedule());
    }

    private void delayedRepeat(Duration delay, Duration interval, Runnable work) {
        tasks.add(proxy.getScheduler().buildTask(this, guard(work))
                .delay(Math.max(50L, delay.toMillis()), TimeUnit.MILLISECONDS)
                .repeat(Math.max(1000L, interval.toMillis()), TimeUnit.MILLISECONDS)
                .schedule());
    }

    /**
     * Wraps scheduled work so one failure does not stop the task repeating.
     *
     * <p>Velocity cancels a repeating task whose body throws. Without this, a single transient
     * error in, say, the sampler would silently end population tracking until the next restart.
     */
    private Runnable guard(Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (Exception e) {
                logger.error("A scheduled statistics task failed; it will run again next tick.", e);
            }
        };
    }

    private void cancelTasks() {
        for (ScheduledTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }

    private void startApiServer() {
        PluginConfig.Api settings = configManager.config().api;
        if (!settings.enabled) {
            return;
        }
        try {
            api = new ApiServer(settings, this, logger);
            api.start();
        } catch (Exception e) {
            api = null;
            logger.error("Could not start the HTTP API on {}:{}.", settings.bind, settings.port, e);
        }
    }

    private void logSummary() {
        WriteQueue.Stats writeStats = writes.stats();
        logger.info("Statistics: {} online, {} samples taken, {} writes applied ({} queued, "
                        + "{} dropped).", sessions.onlineCount(), sampler.samplesTaken(),
                writeStats.applied(), writeStats.queued(), writeStats.dropped());
    }

    /** Points out configurations that will not do what the operator probably expects. */
    private void warnAboutConfiguration() {
        PluginConfig config = configManager.config();

        if (config.placeholders.enabled && config.placeholders.track.isEmpty()) {
            logger.warn("Placeholder tracking is enabled but the track list is empty.");
        }
        if (config.population.enabled
                && config.population.sampleInterval().toMillis() < 250) {
            logger.warn("A population sample interval below 250ms produces far more rows than it "
                    + "produces insight; consider raising it.");
        }
        if (config.privacy.hashAddresses && config.alerts.impossibleTravel) {
            logger.info("Addresses are hashed. Geolocation still runs before hashing, so the "
                    + "impossible-travel check keeps working.");
        }
        if (config.retention.enabled
                && Durations.parse(config.retention.populationSamples, Duration.ZERO).isZero()
                && config.population.sampleInterval().toSeconds() <= 1) {
            logger.warn("Per-second population samples are being kept forever "
                    + "(retention.population-samples is \"0\"). That is roughly 32 million rows "
                    + "a year; set a limit unless you really want all of them.");
        }
    }

    // ------------------------------------------------------------------ accessors

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public PluginConfig config() {
        return configManager.config();
    }

    public Messages messages() {
        return configManager.messages();
    }

    public ZoneId zone() {
        return configManager.config().general.zone();
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Database database() {
        return database;
    }

    public WriteQueue writes() {
        return writes;
    }

    public PlayerDao players() {
        return players;
    }

    public SessionDao sessionDao() {
        return sessionDao;
    }

    public AddressDao addresses() {
        return addresses;
    }

    public EventDao events() {
        return events;
    }

    public PopulationDao population() {
        return population;
    }

    public PlaceholderDao placeholderDao() {
        return placeholderDao;
    }

    public AnnotationDao annotations() {
        return annotations;
    }

    public MaintenanceDao maintenance() {
        return maintenance;
    }

    public Privacy privacy() {
        return privacy;
    }

    public GeoService geo() {
        return geo;
    }

    public WebhookService webhooks() {
        return webhooks;
    }

    public AlertService alerts() {
        return alerts;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public PopulationSampler sampler() {
        return sampler;
    }

    public BridgeService bridge() {
        return bridge;
    }

    public PlaceholderService placeholders() {
        return placeholders;
    }

    public ProfileService profiles() {
        return profiles;
    }

    public RetentionService retention() {
        return retention;
    }

    public ExportService exports() {
        return exports;
    }
}
