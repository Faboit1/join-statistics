# JoinStatistics

Proxy-side player analytics for [Velocity](https://papermc.io/software/velocity). It records what
happens on your network — who connects, from where, for how long, on which backend, running what —
into a local SQLite database, and gives you commands, an HTTP API and exports to ask questions of it.

Nothing leaves your machine unless you switch on a webhook or the optional HTTP geolocation
provider.

---

## What it records

**Identity.** Every account it has seen: first and last sighting, total playtime, session count,
raw connection count, longest and average session, kicks, chat and command counts, server switches,
and the full history of usernames the account has connected under (so looking someone up by a name
they abandoned still finds them).

**Addresses and location.** Every address each account has used, with first/last seen and a
connection count. Each address is resolved once to a country, region, city, coordinates, timezone,
postal code, ISP, organisation and AS number, plus VPN / proxy / hosting / Tor flags and a reverse
DNS name. Resolution runs off the join path, so a slow lookup never delays a login.

**Sessions — with a rejoin grace window.** This is the part most of the numbers depend on. A
session is a stretch of *presence*, not a TCP connection: someone who drops and comes back within
the grace window (30 seconds by default) continues the same session. The reconnect is counted, the
offline stretch is recorded as gap time, and the row keeps its original id. Without that rule, one
bad evening on flaky wifi turns into forty "sessions" averaging ninety seconds, and every statistic
built on session count silently becomes a measurement of the player's ISP.

Each session also stores its client version, brand, locale, virtual host (the domain they actually
typed), view distance, chat mode, skin parts, main hand, mod list, online-mode status, per-server
visits with their own durations, latency samples, and how it ended.

**Population over time.** The online count is sampled on a fixed interval — one second by default —
alongside a per-backend, per-version and per-country breakdown. Raw samples are rolled up into
minute, hour and day buckets so a month-wide query reads a few hundred rows instead of two and a
half million, and retention prunes the raw samples once the rollups exist.

**Backend PlaceholderAPI values.** Velocity cannot see a backend's PlaceholderAPI — expansions run
inside the Bukkit server and read state that never crosses the proxy boundary. The optional
companion plugin answers the proxy's requests over a plugin message channel, so `%vault_eco_balance%`,
`%luckperms_primary_group%`, `%statistic_deaths%` and anything else you list end up on the profile,
with history if you want it.

**Activity shape.** Playtime is attributed to the hour-of-week and calendar-day buckets it actually
falls in — a session from 22:50 to 00:10 contributes to three hour buckets, not one. That is what
makes `/joinstats activity <player>` able to answer "when will this person actually be around".

**Logs.** A generic event log (logins, rejoins, disconnects, server joins and switches, kicks,
denied logins, mod lists, session ends), chat, commands, and server-list pings.

**Flagged patterns.** Shared addresses, VPN and datacentre connections, impossible travel between
two connections, rapid reconnect storms, first joins, name changes, unusually long sessions, and new
concurrent-player records. Each is individually switchable and each is suppressed for twelve hours
after firing for the same account, so the alert list stays readable.

**Your own annotations.** Free-text staff notes and tags on any account.

---

## Installing

1. Drop **`JoinStatistics-Velocity-<version>.jar`** into your Velocity proxy's `plugins/` folder and
   restart. It works on its own; everything below is optional.
2. For backend placeholder values, drop **`JoinStatistics-Companion-<version>.jar`** onto each
   Paper/Spigot backend that has PlaceholderAPI. Both jars must be the same version — the bridge
   between them refuses a mismatched peer rather than misreading it.
3. For offline geolocation, put a `GeoLite2-City.mmdb` in the plugin's data folder. Sign up free at
   [maxmind.com](https://www.maxmind.com/en/geolite2/signup) — the plugin deliberately does not
   download it for you, because their licence requires you to accept their terms yourself.
   `GeoLite2-ASN.mmdb` and `GeoIP2-Anonymous-IP.mmdb` are used too if present. Without any of them
   the plugin falls back to a REST lookup service, which is rate limited and discloses your players'
   addresses to a third party — so it is second in the chain, not first.

Requires **Velocity 3.4.0 or newer** and **Java 17 or newer**. The companion targets the Bukkit 1.20
API and Java 17.

The proxy jar is around 15 MB, almost all of which is SQLite's bundled native library for every
platform it supports. That is the price of the database working on your machine without you
installing anything.

---

## Commands

Everything lives under `/joinstats` (aliases `/js`, `/jstats`, `/playerstats`, all configurable).
Run it bare for a menu filtered to what you can actually use.

| Command | What it does |
| --- | --- |
| `lookup <player>` | The full profile: playtime, sessions, location, client, servers, shared addresses, notes |
| `sessions <player> [page]` | Session history. Merged rejoins are marked `×N` with the gap in the tooltip |
| `activity [player]` | A 7×24 heatmap of when they — or the whole network — are online |
| `alts <player>` | Accounts that have shared an address, exact and by IPv6 prefix |
| `ip <address>` | What is known about an address, and every account that has used it |
| `papi <player>` | Stored backend placeholder values. `papi <player> history <placeholder>` for the series |
| `top [metric] [page]` | Leaderboards: playtime, sessions, connections, longest/average session, chat, commands, kicks, switches, idle, ping, first-seen |
| `top papi <placeholder>` | Rank accounts by a numeric placeholder |
| `online` | Who is on, how long their current session has run, where they are, their latency |
| `counts [window]` | Player count over a window, as a sparkline, with average, range and the all-time peak |
| `servers` | Total playtime per backend |
| `overview` | Network totals: accounts, sessions, playtime, today's activity, top countries and versions |
| `events [player \| type] [page]` | The raw event log |
| `chat <player>` / `commands [player]` | Chat and command history |
| `alerts [type] [page]` | Flagged patterns. `alerts ack` clears them |
| `search <query>` | Find an account by any name it has used |
| `note add\|list\|remove` | Staff notes on an account |
| `tag add\|remove\|list` | Tag accounts and list everyone with a tag |
| `export <what> [csv\|json] [since]` | Dump a table to `plugins/joinstatistics/exports/` |
| `status` | Writer queue depth, geolocation health, bridge state, row counts |
| `prune` | Run retention now |
| `forget <player> confirm` | Erase an account irreversibly |
| `reload` | Re-read the configuration |
| `me` | What the plugin has recorded about you |

### Permissions

| Node | Grants |
| --- | --- |
| `joinstatistics.command` | The base command and its menu |
| `joinstatistics.lookup` | Profiles, leaderboards, counts, activity, events, overview |
| `joinstatistics.sessions` | Session history |
| `joinstatistics.alts` | Shared-address search |
| `joinstatistics.address` | Seeing addresses unmasked, and `ip` |
| `joinstatistics.chatlog` | Chat and command history |
| `joinstatistics.alerts.view` | Reading the alert list |
| `joinstatistics.alerts` | Receiving alerts in chat as they happen |
| `joinstatistics.notes` | Notes and tags |
| `joinstatistics.export` | Exports |
| `joinstatistics.admin` | `status`, `prune`, `forget`, `reload` |
| `joinstatistics.self` | `/joinstats me` |

Subcommands you lack permission for do not appear in the menu or in tab-completion.

---

## Configuration

Two files appear in `plugins/joinstatistics/` on first start: `config.conf` for behaviour and
`messages.conf` for every player-visible string. Both are written back on load, so options added in
a later version appear in your existing file with their documentation at the default value. Every
setting is commented in the file itself; the sections are:

- **`general`** — timezone for all calendar-shaped statistics, debug logging, periodic summaries.
- **`storage`** — database path, WAL and synchronous mode, cache size, read pool, write batching and
  the queue cap that protects the proxy from a stalled disk.
- **`tracking`** — which events are recorded at all, plus exemption lists for players, addresses
  (exact, CIDR or wildcard) and servers.
- **`sessions`** — the rejoin grace window, whether the offline gap counts as playtime, the
  threshold below which a session is recorded but excluded from averages, the maximum session
  length, the heartbeat interval, and the idle threshold.
- **`population`** — sample interval, which breakdowns to capture, and the rollup widths.
- **`geolocation`** — provider chain, MaxMind file paths, REST endpoint and rate limit, cache TTL.
- **`placeholders`** — what to track globally and per server, refresh cadence, history policy.
- **`alerts`** — each detector, with its own thresholds.
- **`webhooks`** — Discord or raw JSON, which events, rate limit and batching.
- **`api`** — the HTTP interface: bind address, port, token, CORS, Prometheus.
- **`privacy`** — address hashing, whether chat and command text is stored, masking in command
  output, self-service lookup, and whether erasure is permitted at all.
- **`retention`** — a separate age limit per table; `"0"` keeps a table indefinitely.
- **`commands`** — aliases, page size, completion limit.

Durations are written with a unit — `30s`, `5m`, `2h`, `7d`, `1w` — and a bare number means seconds.
A value the plugin cannot parse falls back to the documented default and logs a warning rather than
preventing startup.

### Privacy

The defaults collect a lot, because that is what the plugin is for. A few switches are worth
knowing about before you point it at a live network:

- `privacy.hash-addresses` replaces stored addresses with a salted digest. Grouping and alt
  detection keep working; the address itself becomes unrecoverable, including by you. Geolocation
  runs before hashing, so countries survive.
- `privacy.store-chat-content` and `store-command-content` control whether the text is kept or only
  the fact and the length.
- `tracking.sensitive-commands` always wins over both. `/login`, `/register` and friends never have
  their arguments stored regardless of any other setting.
- `retention` deletes on a schedule, per table. Chat defaults to 30 days.
- `/joinstats forget <player> confirm` erases an account across every table in one transaction, and
  drops any address only that account ever used.

---

## HTTP API

Off by default. Enable it under `api`, set a long random `token`, and leave `bind` on loopback
unless there is a TLS-terminating reverse proxy in front — it speaks plain HTTP, and the plugin
refuses to start it with an empty token.

```
GET /api/health
GET /api/overview
GET /api/online
GET /api/servers
GET /api/top?metric=playtime&limit=25
GET /api/counts?from=<ms>&to=<ms>&bucket=1m
GET /api/alerts?type=vpn-detected&limit=50
GET /api/players/{name-or-uuid}
GET /api/players/{name-or-uuid}/sessions
GET /api/players/{name-or-uuid}/placeholders
GET /api/players/{name-or-uuid}/addresses
GET /api/players/{name-or-uuid}/activity
GET /metrics
```

All read-only, all requiring `Authorization: Bearer <token>` (except `/metrics`, optionally).
`/metrics` is Prometheus text format, including per-backend player counts and the plugin's own
health — write queue depth, dropped writes, geolocation hit rate, bridge timeouts.

The `addresses` endpoint deliberately returns locations and networks but not the addresses
themselves.

---

## Building

```sh
./gradlew build
```

Java 21 to build; the output targets Java 17. Artifacts land in `velocity/build/libs/` and
`bukkit/build/libs/`.

GitHub Actions builds every push and pull request and uploads both jars as run artifacts. Pushes to
the default branch also refresh a `dev` prerelease so there is always a downloadable latest build.
Pushing a `v*` tag cuts a real release.

### Layout

```
common/    the wire protocol shared by both plugins
velocity/  the proxy plugin
bukkit/    the backend companion
```

---

## The companion plugin

Optional, tiny, and does exactly one thing: answer the proxy's requests for PlaceholderAPI values.
It stores nothing, registers no commands, and adds no listeners beyond the one it needs to introduce
itself to the proxy.

Placeholder resolution happens on the main server thread, because expansions read live world and
entity state and are not written to be called from anywhere else. That makes the cost yours to
manage, so if a batch takes more than a tick the companion says so in the log once, with what to
change. If PlaceholderAPI is missing it still connects, and every value comes back empty — which is
itself worth recording, because it usually means an expansion is not installed.

---

## Licence

MIT. See [LICENSE](LICENSE).

GeoLite2 databases are licensed separately by MaxMind and are not distributed with this plugin.
