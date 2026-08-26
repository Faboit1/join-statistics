package dev.faboit.joinstats.velocity.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import dev.faboit.joinstats.velocity.util.Durations;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * The whole of {@code config.conf}, as a typed tree.
 *
 * <p>Every field carries a {@link Comment}; Configurate writes those back out when it saves the
 * file, so the shipped config documents itself and newly added options appear (commented) in an
 * existing install on the next start.
 *
 * <p>Durations are declared as strings — {@code "30s"}, {@code "2h"}, {@code "7d"} — and parsed
 * through {@link Durations}, which falls back to the documented default rather than aborting
 * startup when someone fat-fingers a value.
 */
@ConfigSerializable
public class PluginConfig {

    @Comment("Settings that do not belong to any one subsystem.")
    public General general = new General();

    @Comment("Where the collected data lives.")
    public Storage storage = new Storage();

    @Comment("Which events are recorded, and how much detail is kept for each.")
    public Tracking tracking = new Tracking();

    @Comment("Session boundaries, including the rejoin grace window.")
    public Sessions sessions = new Sessions();

    @Comment("Sampling of the online player count over time.")
    public Population population = new Population();

    @Comment("Turning IP addresses into countries, cities and networks.")
    public Geolocation geolocation = new Geolocation();

    @Comment("Pulling PlaceholderAPI values off the backend servers.")
    public Placeholders placeholders = new Placeholders();

    @Comment("Patterns worth flagging as they happen.")
    public Alerts alerts = new Alerts();

    @Comment("Outbound notifications to Discord or any JSON webhook.")
    public Webhooks webhooks = new Webhooks();

    @Comment("A read-only HTTP interface for dashboards and monitoring.")
    public Api api = new Api();

    @Comment("Controls over what is retained and how identifiable it stays.")
    public Privacy privacy = new Privacy();

    @Comment("Automatic pruning of old rows.")
    public Retention retention = new Retention();

    @Comment("In-game command behaviour.")
    public Commands commands = new Commands();

    // ------------------------------------------------------------------ general

    @ConfigSerializable
    public static class General {
        @Comment("""
                Timezone used for every calendar-shaped statistic: daily rollups, the
                hour-of-day activity heatmap, and the day keys in exports.
                Use a IANA zone id such as "Europe/London", or "system" to follow the host.""")
        public String timezone = "system";

        @Comment("Log a line for every recorded event. Extremely noisy — debugging only.")
        public boolean debug = false;

        @Comment("""
                Print a short summary of what was collected every time this interval elapses.
                Set to "0" to disable.""")
        public String summaryInterval = "0";

        public ZoneId zone() {
            if (timezone == null || timezone.isBlank() || timezone.equalsIgnoreCase("system")) {
                return ZoneId.systemDefault();
            }
            try {
                return ZoneId.of(timezone);
            } catch (RuntimeException e) {
                return ZoneId.systemDefault();
            }
        }

        public Duration summary() {
            return Durations.parse(summaryInterval, Duration.ZERO);
        }
    }

    // ------------------------------------------------------------------ storage

    @ConfigSerializable
    public static class Storage {
        @Comment("Database file, relative to this plugin's data folder unless absolute.")
        public String file = "statistics.db";

        @Comment("""
                Write-ahead logging keeps readers from blocking the writer. Leave this on unless
                the database lives on a network filesystem, where WAL is unsafe.""")
        public boolean walMode = true;

        @Comment("""
                How durable each commit must be.
                  FULL   - survives an OS crash; slowest.
                  NORMAL - survives a process crash; the right choice with WAL on.
                  OFF    - fastest, and will corrupt on power loss. Not recommended.""")
        public String synchronousMode = "NORMAL";

        @Comment("SQLite page cache, in mebibytes. Larger helps the leaderboard queries.")
        public int cacheSizeMb = 16;

        @Comment("Connections kept for reads. Writes always go through a single thread.")
        public int readPoolSize = 4;

        @Comment("Give up on a query that has been blocked this long.")
        public String busyTimeout = "10s";

        @Comment("""
                Buffered writes are flushed when either limit is hit, whichever comes first.
                Larger batches cost less I/O but widen the window of data lost to a hard kill.""")
        public int batchSize = 250;

        @Comment("Maximum time a buffered write waits before being flushed.")
        public String flushInterval = "2s";

        @Comment("""
                Drop writes once the queue exceeds this many pending operations. Protects the
                proxy from unbounded memory growth if the disk stalls. 0 disables the cap.""")
        public int maxQueuedWrites = 50_000;

        @Comment("Run VACUUM on startup when the file has more than this fraction of free pages.")
        public double vacuumThreshold = 0.25;

        public Duration busy() {
            return Durations.parse(busyTimeout, Duration.ofSeconds(10));
        }

        public Duration flush() {
            return Durations.parse(flushInterval, Duration.ofSeconds(2));
        }
    }

    // ------------------------------------------------------------------ tracking

    @ConfigSerializable
    public static class Tracking {
        @Comment("Record every connection attempt, including ones rejected before login.")
        public boolean logins = true;

        @Comment("Record disconnects, with the reason where Velocity exposes one.")
        public boolean disconnects = true;

        @Comment("Record each backend server the player is routed to, and time spent on it.")
        public boolean serverSwitches = true;

        @Comment("Record kicks from a backend, and where the player was sent afterwards.")
        public boolean kicks = true;

        @Comment("""
                Record server-list pings (the entry in the multiplayer menu). High volume on a
                public address — every scanner on the internet shows up here.""")
        public boolean pings = false;

        @Comment("Store the virtual host (the domain the player typed) for each connection.")
        public boolean virtualHosts = true;

        @Comment("Store protocol version, client brand and mod list.")
        public boolean clientDetails = true;

        @Comment("""
                Store client settings: locale, view distance, chat mode, visible skin parts and
                main hand. Useful for demographics; harmless but personal.""")
        public boolean clientSettings = true;

        @Comment("Sample each player's measured latency on this interval. \"0\" disables.")
        public String pingSampleInterval = "60s";

        @Comment("Record chat messages. See privacy.store-chat-content for the body itself.")
        public boolean chat = true;

        @Comment("Record commands. See privacy.store-command-content for arguments.")
        public boolean commands = true;

        @Comment("Commands whose arguments are never stored, whatever the privacy settings say.")
        public List<String> sensitiveCommands = new ArrayList<>(Arrays.asList(
                "login", "register", "l", "reg", "changepassword", "changepass", "unregister",
                "premium", "2fa", "authme", "email", "password"));

        @Comment("Never record anything at all for these players.")
        public List<String> exemptPlayers = new ArrayList<>();

        @Comment("Never record anything for connections from these addresses or CIDR ranges.")
        public List<String> exemptAddresses = new ArrayList<>();

        @Comment("Skip these backend servers entirely (a lobby used only for routing, say).")
        public List<String> ignoredServers = new ArrayList<>();

        public Duration pingSample() {
            return Durations.parse(pingSampleInterval, Duration.ofSeconds(60));
        }
    }

    // ------------------------------------------------------------------ sessions

    @ConfigSerializable
    public static class Sessions {
        @Comment("""
                A player who reconnects within this window continues their previous session
                instead of starting a new one. This is what stops a single lag spike from being
                counted as two sessions with a bogus one-second gap between them.""")
        public String rejoinGrace = "30s";

        @Comment("""
                Whether the offline gap inside a merged session counts toward playtime.
                false  - playtime is the sum of the connected stretches (recommended).
                true   - playtime is simply end minus start.""")
        public boolean countGapAsPlaytime = false;

        @Comment("""
                Sessions shorter than this are still recorded but excluded from averages and
                leaderboards, so failed handshakes do not drag the numbers down.""")
        public String minimumMeaningfulSession = "10s";

        @Comment("""
                Force a session closed after this long regardless of activity — a safety net for
                a connection the proxy never sees drop. "0" disables.""")
        public String maximumSession = "24h";

        @Comment("""
                How often an open session's heartbeat is written. If the proxy is killed, open
                sessions are closed at their last heartbeat on the next start, so the error is
                bounded by this interval instead of being unbounded.""")
        public String heartbeatInterval = "30s";

        @Comment("""
                Treat a player as idle after this long without a chat message, command or server
                switch. Idle time is tracked separately from playtime. "0" disables.""")
        public String idleAfter = "10m";

        public Duration grace() {
            return Durations.parse(rejoinGrace, Duration.ofSeconds(30));
        }

        public Duration minimumMeaningful() {
            return Durations.parse(minimumMeaningfulSession, Duration.ofSeconds(10));
        }

        public Duration maximum() {
            return Durations.parse(maximumSession, Duration.ofHours(24));
        }

        public Duration heartbeat() {
            return Durations.parse(heartbeatInterval, Duration.ofSeconds(30));
        }

        public Duration idle() {
            return Durations.parse(idleAfter, Duration.ofMinutes(10));
        }
    }

    // ------------------------------------------------------------------ population

    @ConfigSerializable
    public static class Population {
        @Comment("Sample the online player count on a fixed interval.")
        public boolean enabled = true;

        @Comment("""
                How often to take a sample. "1s" gives second-by-second resolution, which is the
                point of this feature; the samples are tiny and are rolled up and pruned below.""")
        public String interval = "1s";

        @Comment("Also store a per-backend-server breakdown alongside the proxy total.")
        public boolean perServer = true;

        @Comment("Record the number of players in each protocol version at each sample.")
        public boolean perVersion = false;

        @Comment("Record a per-country breakdown at each sample. Needs geolocation enabled.")
        public boolean perCountry = false;

        @Comment("""
                Aggregate raw samples into coarser buckets so long-range queries stay fast.
                Each entry is a bucket width; a rollup row keeps min, max, average and sample
                count for the window.""")
        public List<String> rollups = new ArrayList<>(Arrays.asList("1m", "1h", "1d"));

        @Comment("How often the rollup job runs.")
        public String rollupInterval = "1m";

        public Duration sampleInterval() {
            return Durations.parse(interval, Duration.ofSeconds(1));
        }

        public Duration rollupEvery() {
            return Durations.parse(rollupInterval, Duration.ofMinutes(1));
        }

        public List<Duration> rollupBuckets() {
            List<Duration> out = new ArrayList<>();
            for (String raw : rollups) {
                Duration parsed = Durations.parse(raw, Duration.ZERO);
                if (!parsed.isZero() && !parsed.isNegative()) {
                    out.add(parsed);
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ geolocation

    @ConfigSerializable
    public static class Geolocation {
        @Comment("Resolve addresses to a country, city and network operator.")
        public boolean enabled = true;

        @Comment("""
                Providers are tried in order until one answers.
                  maxmind - a local GeoLite2/GeoIP2 .mmdb file. Offline, fast, no rate limit.
                  http    - a REST lookup service. No local database needed, but rate limited
                            and it discloses your players' addresses to a third party.""")
        public List<String> providers = new ArrayList<>(Arrays.asList("maxmind", "http"));

        public MaxMind maxmind = new MaxMind();
        public HttpLookup http = new HttpLookup();

        @Comment("How long a resolved address is reused before being looked up again.")
        public String cacheTtl = "7d";

        @Comment("Entries held in the in-memory lookup cache.")
        public int cacheSize = 10_000;

        @Comment("Re-resolve an address on every join instead of trusting the stored answer.")
        public boolean alwaysRefresh = false;
    }

    @ConfigSerializable
    public static class MaxMind {
        @Comment("""
                Path to a GeoLite2-City.mmdb (or GeoIP2-City.mmdb), relative to the data folder.
                Get a free copy from https://www.maxmind.com/en/geolite2/signup — this plugin
                deliberately does not download it for you, because their licence requires you to
                accept their terms yourself.""")
        public String cityDatabase = "GeoLite2-City.mmdb";

        @Comment("Optional GeoLite2-ASN.mmdb, for the network operator and AS number.")
        public String asnDatabase = "GeoLite2-ASN.mmdb";

        @Comment("Optional GeoIP2-Anonymous-IP.mmdb, for VPN/proxy/hosting detection.")
        public String anonymousDatabase = "GeoIP2-Anonymous-IP.mmdb";

        @Comment("Reload the .mmdb files without a restart when they change on disk.")
        public boolean watchForUpdates = true;
    }

    @ConfigSerializable
    public static class HttpLookup {
        @Comment("""
                Endpoint template. {ip} is substituted with the address being resolved.
                The default is ip-api.com's free tier: 45 requests per minute, no key, and it
                returns proxy/hosting flags. Read their terms before using it commercially.""")
        public String endpoint =
                "http://ip-api.com/json/{ip}?fields=status,message,continent,continentCode,country,"
                        + "countryCode,region,regionName,city,district,zip,lat,lon,timezone,offset,"
                        + "currency,isp,org,as,asname,reverse,mobile,proxy,hosting,query";

        @Comment("Sent as an Authorization header when non-empty.")
        public String authorization = "";

        @Comment("Requests per minute allowed against the endpoint. Excess lookups are deferred.")
        public int rateLimitPerMinute = 40;

        @Comment("Give up on a lookup after this long.")
        public String timeout = "5s";

        public Duration requestTimeout() {
            return Durations.parse(timeout, Duration.ofSeconds(5));
        }
    }

    // ------------------------------------------------------------------ placeholders

    @ConfigSerializable
    public static class Placeholders {
        @Comment("""
                Ask backend servers to resolve PlaceholderAPI strings for each player.
                Requires the JoinStatistics-Companion jar on those servers, plus PlaceholderAPI.""")
        public boolean enabled = true;

        @Comment("""
                Placeholders to resolve, exactly as PlaceholderAPI expects them.
                Anything the backend cannot resolve is stored as an empty value, which is itself
                worth knowing — it usually means an expansion is missing.""")
        public List<String> track = new ArrayList<>(Arrays.asList(
                "%player_world%",
                "%player_x%",
                "%player_y%",
                "%player_z%",
                "%player_health%",
                "%player_food_level%",
                "%player_exp_level%",
                "%player_gamemode%",
                "%player_ping%",
                "%vault_eco_balance%",
                "%vault_rank%",
                "%luckperms_primary_group%",
                "%statistic_deaths%",
                "%statistic_mob_kills%",
                "%statistic_player_kills%",
                "%statistic_time_played%"));

        @Comment("""
                Extra placeholders resolved only on named servers. Use this for expansions that
                exist on one backend — a minigame's stats, for instance.
                Format: "servername" = [ "%placeholder%", ... ]""")
        public java.util.Map<String, List<String>> perServer = new java.util.LinkedHashMap<>();

        @Comment("How often to refresh placeholder values for every online player.")
        public String refreshInterval = "5m";

        @Comment("""
                Wait this long after a player lands on a backend before the first request, so
                that whatever the backend does on join has finished first.""")
        public String joinDelay = "5s";

        @Comment("Also resolve one final time just before a session is finalised.")
        public boolean captureOnQuit = true;

        @Comment("""
                Keep every observed value, not just the latest one, so a placeholder can be
                graphed over time. Costs one row per placeholder per refresh that changed.""")
        public boolean keepHistory = true;

        @Comment("Only write a history row when the value actually changed.")
        public boolean historyOnChangeOnly = true;

        @Comment("Give up on a backend that has not answered a request in this long.")
        public String requestTimeout = "10s";

        @Comment("Placeholders resolved in one request. Lower this if backends complain.")
        public int batchSize = 64;

        public Duration refresh() {
            return Durations.parse(refreshInterval, Duration.ofMinutes(5));
        }

        public Duration delayAfterJoin() {
            return Durations.parse(joinDelay, Duration.ofSeconds(5));
        }

        public Duration timeout() {
            return Durations.parse(requestTimeout, Duration.ofSeconds(10));
        }
    }

    // ------------------------------------------------------------------ alerts

    @ConfigSerializable
    public static class Alerts {
        @Comment("Record notable patterns to the alerts table as they are detected.")
        public boolean enabled = true;

        @Comment("Also show alerts in chat to staff holding joinstatistics.alerts.")
        public boolean notifyStaff = true;

        @Comment("Flag a join from an address already used by a different account.")
        public boolean altAccounts = true;

        @Comment("""
                Ignore shared-address matches older than this. Residential addresses are
                reassigned constantly, so a match from two years ago means nothing.""")
        public String altMaxAge = "90d";

        @Comment("Match alt accounts on the IPv6 /64 prefix as well as the exact address.")
        public boolean altMatchSubnet = true;

        @Comment("Flag connections that geolocation marks as a VPN, proxy or hosting range.")
        public boolean vpnConnections = true;

        @Comment("""
                Flag a player whose location moved further than could be travelled in the time
                between two connections. Strong signal of a shared or stolen account.""")
        public boolean impossibleTravel = true;

        @Comment("Assumed maximum travel speed, in km/h, for the check above.")
        public double maxTravelKmh = 900.0;

        @Comment("Flag a player reconnecting more than this many times inside the window.")
        public boolean rapidRejoin = true;

        public int rapidRejoinThreshold = 5;
        public String rapidRejoinWindow = "2m";

        @Comment("Flag the first time an account is ever seen.")
        public boolean firstJoin = true;

        @Comment("Flag a player connecting under a username we have not recorded for them.")
        public boolean nameChange = true;

        @Comment("Flag a session that lasts longer than this. \"0\" disables.")
        public String longSession = "8h";

        @Comment("""
                Flag when the concurrent player count crosses this value, so a record peak or a
                sudden collapse is captured. 0 disables.""")
        public int populationSpike = 0;

        public Duration altWindow() {
            return Durations.parse(altMaxAge, Duration.ofDays(90));
        }

        public Duration rejoinWindow() {
            return Durations.parse(rapidRejoinWindow, Duration.ofMinutes(2));
        }

        public Duration longSessionAfter() {
            return Durations.parse(longSession, Duration.ofHours(8));
        }
    }

    // ------------------------------------------------------------------ webhooks

    @ConfigSerializable
    public static class Webhooks {
        @Comment("Post events to an HTTP endpoint as they happen.")
        public boolean enabled = false;

        @Comment("Target URL. A Discord webhook URL works as-is.")
        public String url = "";

        @Comment("""
                Payload shape.
                  discord - a Discord embed, ready for a channel webhook.
                  raw     - the event as plain JSON, for your own consumer.""")
        public String format = "discord";

        @Comment("""
                Which events to send. Valid values:
                join, quit, session-end, first-join, name-change, server-switch, kick,
                alt-detected, vpn-detected, impossible-travel, rapid-rejoin, long-session,
                population-peak""")
        public List<String> events = new ArrayList<>(Arrays.asList(
                "first-join", "alt-detected", "vpn-detected", "impossible-travel"));

        @Comment("Never send more than this many requests per minute; the rest are dropped.")
        public int rateLimitPerMinute = 20;

        @Comment("Batch events arriving inside this window into a single request.")
        public String batchWindow = "5s";

        @Comment("Include the player's address in the payload. Off by default on purpose.")
        public boolean includeAddress = false;

        @Comment("Give up on a delivery after this long.")
        public String timeout = "10s";

        public Duration batch() {
            return Durations.parse(batchWindow, Duration.ofSeconds(5));
        }

        public Duration requestTimeout() {
            return Durations.parse(timeout, Duration.ofSeconds(10));
        }

        public boolean sends(String event) {
            for (String candidate : events) {
                if (candidate.equalsIgnoreCase(event) || candidate.equals("*")) {
                    return true;
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ api

    @ConfigSerializable
    public static class Api {
        @Comment("Serve the collected data over read-only HTTP.")
        public boolean enabled = false;

        @Comment("""
                Interface to bind. Leave as 127.0.0.1 unless you have put a reverse proxy with
                TLS in front — this server speaks plain HTTP and must not face the internet.""")
        public String bind = "127.0.0.1";

        public int port = 8787;

        @Comment("""
                Bearer token required on every request. Generate a long random string.
                An empty token with the API enabled means anyone who can reach the port can read
                every address and session you have stored, so the plugin refuses to start the
                server in that state.""")
        public String token = "";

        @Comment("Value for Access-Control-Allow-Origin. Empty sends no CORS header at all.")
        public String corsOrigin = "";

        @Comment("Expose /metrics in Prometheus text format.")
        public boolean prometheus = true;

        @Comment("Allow /metrics without the bearer token, as scrapers usually expect.")
        public boolean prometheusUnauthenticated = false;

        @Comment("Rows any single query may return.")
        public int maxPageSize = 500;

        @Comment("Requests allowed per minute, per address.")
        public int rateLimitPerMinute = 120;
    }

    // ------------------------------------------------------------------ privacy

    @ConfigSerializable
    public static class Privacy {
        @Comment("""
                Replace stored addresses with a salted digest. Alt detection and per-address
                grouping keep working; the actual address becomes unrecoverable, including by
                you. Geolocation is resolved before hashing, so countries are still available.""")
        public boolean hashAddresses = false;

        @Comment("""
                Secret used for the digest above. Leave empty and one is generated on first
                start and written back here. Changing it orphans every address already stored.""")
        public String addressSalt = "";

        @Comment("Store the text of chat messages, not just the fact that one was sent.")
        public boolean storeChatContent = true;

        @Comment("Store command arguments, not just the command name.")
        public boolean storeCommandContent = true;

        @Comment("Mask addresses in command output for staff without joinstatistics.viewip.")
        public boolean maskAddressesInCommands = true;

        @Comment("""
                Let a player run /joinstats me to see what has been collected about them.
                Requires the joinstatistics.self permission.""")
        public boolean selfServiceLookup = true;

        @Comment("""
                Let /joinstats forget <player> erase a player irreversibly. The command always
                requires joinstatistics.admin; this switch removes it entirely if you would
                rather nobody could.""")
        public boolean allowForget = true;
    }

    // ------------------------------------------------------------------ retention

    @ConfigSerializable
    public static class Retention {
        @Comment("Delete rows older than the limits below.")
        public boolean enabled = true;

        @Comment("How often the pruning job runs.")
        public String interval = "6h";

        @Comment("Raw per-second population samples. Rollups are kept for much longer.")
        public String populationSamples = "7d";

        @Comment("Per-bucket rollups of the population count. \"0\" keeps them forever.")
        public String populationRollups = "0";

        @Comment("The generic event log (joins, switches, kicks and so on).")
        public String events = "180d";

        @Comment("Chat messages.")
        public String chat = "30d";

        @Comment("Commands.")
        public String commands = "90d";

        @Comment("Server-list pings.")
        public String pings = "7d";

        @Comment("Individual session rows. Aggregate playtime on the profile is never pruned.")
        public String sessions = "365d";

        @Comment("Placeholder history rows.")
        public String placeholderHistory = "90d";

        @Comment("Alerts.")
        public String alerts = "365d";

        @Comment("""
                Delete a player entirely once they have not connected for this long.
                "0" — the default — never deletes a profile.""")
        public String inactiveProfiles = "0";

        public Duration every() {
            return Durations.parse(interval, Duration.ofHours(6));
        }
    }

    // ------------------------------------------------------------------ commands

    @ConfigSerializable
    public static class Commands {
        @Comment("Extra names for /joinstats.")
        public List<String> aliases = new ArrayList<>(Arrays.asList("js", "jstats", "playerstats"));

        @Comment("Rows per page in paginated output.")
        public int pageSize = 8;

        @Comment("Names offered by tab-completion for offline players.")
        public int completionLimit = 40;

        @Comment("Announce a returning player's stats to staff when they join.")
        public boolean joinSummaryToStaff = false;
    }

    /** Case-insensitive membership test used by the several exemption lists above. */
    public static boolean containsIgnoreCase(List<String> values, String candidate) {
        if (values == null || candidate == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Normalises a command label the way {@link Tracking#sensitiveCommands} expects it. */
    public static String commandName(String input) {
        String trimmed = input.startsWith("/") ? input.substring(1) : input;
        int space = trimmed.indexOf(' ');
        String head = space < 0 ? trimmed : trimmed.substring(0, space);
        int colon = head.indexOf(':');
        return (colon < 0 ? head : head.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }
}
