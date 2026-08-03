# TickSentry

A Minecraft Paper plugin that watches server performance in the background, works out **where**
the lag is coming from, and posts a readable alert to Discord.

Its edge over spark is not depth of analysis - it is that nobody has to run anything. The alert
arrives on its own, with a ready-to-paste command.

## What it does

- Measures tick time (MSPT) and TPS every tick using a rolling average, so a single spike never
  triggers an alarm.
- Once the threshold holds, scans every loaded chunk and names the five most suspicious ones.
  The scan is spread across ticks with a 3 ms budget each, so it never stalls the server itself.
- Guesses the cause: mob farm, dropped items, redstone/hoppers, player crowd, general entity overload.
- Sends a Discord embed with coordinates, the cause and a suggested action - written for an admin,
  not for a profiler user.
- Cooldown between alerts (5 minutes by default) so the channel does not get spammed.
- Posts a separate green "server is back to normal" message with the incident duration.
- Stores incidents in SQLite, so history survives restarts and `/lagwatch stats` can show which
  hour of the day the server struggles most.
- Ships a **web panel** with a tick time chart and an incident list - no hosting, no external
  service, everything runs on the machine that runs the server.
- If **spark** is installed, adds its statistics (mean and 95th percentile tick time).
- If **PlaceholderAPI** is installed, exposes `%ticksentry_tps%`, `%ticksentry_mspt%` and more.

## Requirements

- **Minecraft 1.16.5 up to the newest release** - Paper, Spigot or any fork of them
- **Java 11 or newer**

The plugin is built against the 1.16.5 API and compiled to Java 11 bytecode, so a single jar
covers every version from 1.16.5 onwards. From 1.17 the Java requirement takes care of itself,
because Minecraft itself demands a newer runtime:

| Minecraft | Java required by the server | TickSentry |
| --- | --- | --- |
| 1.16.5 | 8+ (Paper recommends 11) | works on Java 11+ |
| 1.17.x | 16+ | works |
| 1.18 - 1.20.4 | 17+ | works |
| 1.20.5 and newer | 21+ | works |

The only case that will not run is a 1.16 server still on Java 8. Bumping such a server to
Java 11 is recommended by Paper anyway.

Both ends of that range are verified on real servers, not just by compiling: **Paper 1.16.5
(build 794) on Java 11** and **Paper 1.21.10 on Java 21**. On 1.16.5 the plugin loaded, pulled
the SQLite driver, served the web panel, answered every command, spotted a chunk holding 454 cows
as a mob farm, fired a sustained-lag alert and posted the recovery notice. Spark is absent there,
which the reflective bridge handles silently.

## Building

```bash
./gradlew build
```

The result lands in `build/libs/TickSentry-1.0.0.jar` - drop it into `plugins/` and restart.
Building needs a JDK 21 toolchain, but the produced bytecode targets Java 11.

## Configuration

The first startup creates `plugins/TickSentry/config.yml`:

```yaml
monitor:
  mspt-threshold-ms: 50      # above this many ms the server counts as overloaded
  sustained-seconds: 10      # how long the threshold must hold before an alert
  scan-cooldown-seconds: 300 # minimum gap between alerts
  rolling-average-ticks: 100 # rolling average window
  recovery-alert: true       # message once the server recovers
  recovery-seconds: 15       # how much calm ends an incident
discord:
  enabled: true
  webhook-url: ""
  mention-role-id: ""        # optional role id to ping (digits only)
scan:
  ignored-worlds: []
  top-chunks-count: 5
storage:
  enabled: true              # false = history in memory only, lost on restart
  keep-days: 30              # delete entries older than this (0 = keep everything)
dashboard:
  enabled: false             # web panel in the browser
  bind: "127.0.0.1"          # this machine only; 0.0.0.0 exposes it to the network
  port: 8080
  token: ""                  # empty = the plugin generates one and writes it here
```

Get a webhook like this: channel settings on Discord → Integrations → Webhooks → New Webhook →
copy the URL. Anyone holding that URL can post to your channel, so treat it like a password.

## Commands

All of them require the `ticksentry.admin` permission (default: OP).

| Command | What it does |
| --- | --- |
| `/lagwatch status` | current TPS, tick time, monitoring and cooldown state |
| `/lagwatch report` | forces a scan and prints the result in chat |
| `/lagwatch report discord` | the same, but also posts to the webhook (handy for checking the setup) |
| `/lagwatch history` | recent incidents, including ones from before a restart |
| `/lagwatch stats [days]` | summary: how many incidents, caused by what, at which hour (7 days by default) |
| `/lagwatch reload` | reloads `config.yml` |

Aliases: `/ts`, `/ticksentry`.

## Placeholders (require PlaceholderAPI)

| Placeholder | Returns |
| --- | --- |
| `%ticksentry_tps%` | TPS, e.g. `19.9` |
| `%ticksentry_mspt%` | average tick time in ms |
| `%ticksentry_peak_ms%` | longest freeze in the sample window |
| `%ticksentry_status%` | `OK` or `LAG` |
| `%ticksentry_monitoring%` | `running` or `stopped` |
| `%ticksentry_last_category%` | cause of the last incident |
| `%ticksentry_incidents_24h%` | incidents in the last 24 hours |

## Web panel

Set `dashboard.enabled: true` and restart. The console prints a ready address:

```
[TickSentry] Web panel: http://127.0.0.1:8080/?token=a15e51d7...
```

Paste it into a browser. The panel shows TPS, tick time, longest freeze, player count, a chart of
tick time over the last hour (with the alert threshold marked) and a table of recent incidents.
It refreshes itself every 2 seconds.

**Nothing needs hosting.** The panel is an HTTP server embedded in the plugin (`HttpServer` from
the JDK) running on the same machine as the game server. There is no central service, no account
and no cost - everyone who installs the plugin gets their own panel.

### Security

Every request must present a token, either in the address (`?token=...`) or in an `X-Auth-Token`
header. The token is generated at random on first start and saved to `config.yml`.

**The connection is plain HTTP, with no encryption.** That is why the panel listens on
`127.0.0.1` by default, reachable only from the machine hosting the server. To reach it from
another device there are two safe routes:

1. **An SSH tunnel** (simplest): `ssh -L 8080:127.0.0.1:8080 user@your-server`, then open
   `http://127.0.0.1:8080` locally.
2. **A reverse proxy with HTTPS** (nginx, Caddy) in front of the panel.

Setting `bind: "0.0.0.0"` exposes the panel to the network unencrypted - the token then travels
in clear text and anyone in between can capture it. The plugin warns about this in the console.

## Trying it out

A full step-by-step guide lives in [TESTING.md](TESTING.md) - from building the jar, through
generating real lag with 2000 cows, to checking the panel and the Discord alert. It takes about
15 minutes.

The short version: set `sustained-seconds: 3`, run `/lagwatch reload`, stand still and keep
doubling cows with
`/execute as @e[type=cow,distance=..10] at @s run summon minecraft:cow ~ ~ ~`.
The alert should point at exactly the chunk you are standing in.

## Known limitations (deliberate)

- There is no call-stack sampling like spark does. The plugin correlates high MSPT with anomalous
  chunk contents, so it **will not** catch lag coming from a plugin, world saving or terrain
  generation - in that case it reports the "No obvious source" category and suggests running spark.
- The cost weights for entities and block entities (`HotspotAnalyzer`) are approximations, not
  profiling results. They are tuned so that 40 hoppers outweigh 40 chests, and 200 dropped items
  weigh less than 200 villagers.

## Layout

```
dev/poleszczuk/ticksentry
├── TickSentryPlugin         wiring
├── monitor/
│   ├── TickMonitor          per-tick MSPT/TPS measurement and sustained-breach detection
│   ├── ChunkHotspotScanner  reads chunks through Bukkit (main thread, spread across ticks)
│   ├── ChunkStat            chunk snapshot, free of any Bukkit dependency
│   ├── HotspotAnalyzer      weights, ranking and categorisation - all the logic, fully testable
│   ├── LagCategory          the cause categories
│   ├── LagEvent             one incident
│   └── SparkBridge          optional data from spark (reflection)
├── discord/
│   ├── DiscordWebhookClient async delivery on its own thread
│   └── EmbedBuilder         embed JSON without any library
├── commands/LagWatchCommand
├── config/ConfigManager
├── placeholders/TickSentryExpansion  optional PlaceholderAPI placeholders
├── util/Json                bare minimum for hand-rolled JSON
├── web/
│   ├── DashboardServer      JDK HttpServer, token auth, three endpoints
│   ├── LiveSnapshot         state snapshot assembled on the main thread
│   └── MsptHistory          ring buffer of chart samples
└── storage/
    ├── AlertStore           incident store interface
    ├── SqliteAlertStore     disk storage, all I/O on a separate thread
    ├── MemoryAlertStore     fallback when storage is off or the database fails
    ├── StoredIncident       flattened incident (one database row)
    └── IncidentStats        period summary: causes, spread across the day
```

The SQLite driver is not bundled into the jar - it is declared under `libraries` in `plugin.yml`,
so Paper fetches it on first start. That keeps the jar small (~60 KB) and avoids version clashes
with other plugins.

Unit tests (`./gradlew test`) cover the chunk scoring logic, JSON building, statistics and the
sample buffer - 34 tests, with no Bukkit mocking.

## Where the thresholds come from

The weights and thresholds in `HotspotAnalyzer` were calibrated on a running Paper 1.21.10 server:

- `MIN_INTERESTING_SCORE = 80` - at 25, an ordinary chunk holding a few dozen falling blocks
  during terrain generation was labelled a "mob farm".
- `FALLING_BLOCK`, `ARROW` and `SNOWBALL` carry reduced weights - they appear in bulk but briefly.
- A player weighs 5.0, because they keep chunks loaded around them and generate network traffic.

If alerts feel too eager or too quiet on your server, those are the first knobs to turn.

## Licence

MIT - see [LICENSE](LICENSE).
