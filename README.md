# TickSentry

A plugin for Minecraft servers that watches performance and tells you **what is causing lag**.

When the server slows down, TickSentry checks every loaded chunk, finds the worst one, and sends
you a message on Discord with the coordinates and a command you can paste to fix it.

You do not have to run anything or watch anything. It works on its own.

```
Sustained lag detected: MSPT 80.1 ms, TPS 18.29. Cause: Mob farm.
 - world @ 1608, 1608 (entities: 1292, block entities: 0)
Suggestion: Go there (/tp 1608 ~ 1608). Suspected farm: 841x cow.
            Quick fix: /kill @e[type=cow,x=1600,y=-64,z=1600,dx=16,dy=384,dz=16]
```

## What it does

- Checks how long each tick takes. One slow tick is normal, so it only warns you when the server
  stays slow for a while.
- Finds the chunk causing the problem and tells you what is in it.
- Names the cause: a mob farm, dropped items, hoppers, a crowd of players, or too many entities.
- Tells you **whose** it is, by asking WorldGuard, GriefPrevention or Towny who owns the land -
  and, when none of them is installed, who was last standing there.
- Times every other plugin's event handlers, so it can name the plugin that is eating the tick
  instead of blaming a chunk that did nothing wrong.
- Watches memory too, so it can tell you when the freeze came from the garbage collector or from
  the server running out of RAM - things that counting mobs would never show.
- Sends the alert to Discord, then sends a second message when the server is fine again.
- Tells admins who are in the game, so you do not have to watch Discord.
- Saves every incident, so you can ask what time of day your server usually struggles.
- Has a small web page with a chart, and a /metrics endpoint for Prometheus and Grafana.
- Can clean up after itself - sweep dropped items, thin out mob pile-ups - but only if you
  explicitly turn that on. By default it changes nothing.

## Install

1. Download `TickSentry-1.0.0.jar` (or build it - see the bottom of this page).
2. Put it in your server's `plugins` folder.
3. Restart the server.

That is all. The plugin starts working right away and writes its settings to
`plugins/TickSentry/config.yml`.

## Which versions work

Minecraft **1.16.5 and anything newer**. You need **Java 11 or newer**.

| Minecraft | Java your server needs | TickSentry |
| --- | --- | --- |
| 1.16.5 | 8 or newer | works on Java 11+ |
| 1.17 | 16 or newer | works |
| 1.18 - 1.20.4 | 17 or newer | works |
| 1.20.5 and newer | 21 or newer | works |

From 1.17 onwards you do not have to think about Java at all, because Minecraft already asks for
a newer version than the plugin does. The only setup that will not work is a 1.16 server still
running Java 8.

Both ends were tested on real servers: Paper 1.16.5 on Java 11, and Paper 1.21.10 on Java 21.

**Folia is not supported.** Folia ticks each region of the world on its own thread, which removes
the two things this plugin is built on: one main thread that Bukkit calls can be made from, and
one tick time that describes the whole server. Rather than report numbers it cannot measure,
TickSentry notices Folia, explains itself in the console, and shuts down. Paper and Spigot are
unaffected.

## Commands

You need to be an operator (or have the `ticksentry.admin` permission). There is a second
permission, `ticksentry.alerts`, which only decides who sees lag warnings in the game chat -
handy if you want moderators warned without giving them the commands.

| Command | What it does |
| --- | --- |
| `/lagwatch status` | shows TPS and tick time right now |
| `/lagwatch report` | checks the server immediately and lists the 5 worst chunks |
| `/lagwatch report discord` | same, but also sends it to Discord (good for testing your webhook) |
| `/lagwatch plugins` | shows which plugins spent the most time in their event handlers |
| `/lagwatch history` | shows past incidents, even from before a restart |
| `/lagwatch offenders` | shows the chunks that keep causing incidents, not just the last one |
| `/lagwatch stats` | shows a summary: how many incidents, what caused them, at what time of day |
| `/lagwatch reload` | loads the settings file again |

You can type `/ts` instead of `/lagwatch`.

`/lagwatch stats` takes a number of days, for example `/lagwatch stats 30`. It uses 7 days if you
do not give one.

## Settings

The file is `plugins/TickSentry/config.yml`. After changing it, type `/lagwatch reload`.

```yaml
monitor:
  mspt-threshold-ms: 50      # a tick slower than this counts as a problem
  sustained-seconds: 10      # how long the problem must last before you get an alert
  scan-cooldown-seconds: 300 # wait this long before sending another alert
  rolling-average-ticks: 100 # how many ticks are averaged together
  recovery-alert: true       # send a message when the server is fine again
  recovery-seconds: 15       # how long it must stay fine before that message
  in-game-alerts: true       # also tell admins who are online
  adaptive-threshold:
    enabled: false           # learn what "normal" is on this server, see below

discord:
  enabled: true
  webhook-url: ""            # paste your webhook link here
  mention-role-id: ""        # optional: a role to ping, numbers only

profiler:
  enabled: true              # time other plugins' event handlers
  window-seconds: 30         # how far back a plugin report looks

scan:
  ignored-worlds: []         # worlds to skip, for example ['world_the_end']
  top-chunks-count: 5        # how many bad chunks to list

storage:
  enabled: true              # save incidents to a file so they survive a restart
  keep-days: 30              # delete anything older than this (0 = keep forever)
  offender-days: 7           # window for deciding which chunks keep coming back

remediation:
  enabled: false             # let the plugin remove things? see the section below
  dry-run: true              # ...and even then, only report at first

dashboard:
  enabled: false             # the web page
  bind: "127.0.0.1"          # only this computer can open it
  port: 8080
  token: ""                  # leave empty, the plugin fills it in
  metrics: true              # also serve /metrics for Prometheus

weights:                     # optional, see below
  entities: {}
  block-entities: {}
```

### Tuning what counts as expensive

The plugin scores each chunk by what is in it. A hopper counts for more than a chest, a villager
for more than a dropped item. If those guesses do not match your server, change them:

```yaml
weights:
  entities:
    VILLAGER: 5.0   # villagers hurt more here than the default 3.0
    ITEM: 0.2       # I sweep dropped items often, stop blaming them
  block-entities:
    HOPPER: 5.0     # my sorting systems are the usual suspect
```

Anything you do not list keeps its built-in value, so you only write down what you want changed.
A value of 1.0 means "as expensive as an average mob".

### What the numbers mean

**Tick time** (also called MSPT) is how long the server needs to do one round of work. It should
finish within 50 ms. A healthy server takes about 5-25 ms. If it takes longer than 50 ms, the
server cannot keep up and players feel it.

**TPS** is the same thing seen from the other side: how many rounds fit into one second. 20 is
perfect. Below 18 people start noticing.

### A threshold that fits your server

A fixed 50 ms suits the average server and nobody else. A box that habitually runs at 45 ms gets
an alert for every hiccup until the admin gives up and turns alerts off. A box that runs at 8 ms
can quintuple its tick time - a real regression worth investigating - and never say a word.

```yaml
monitor:
  adaptive-threshold:
    enabled: true
    multiplier: 2.0          # alert when the server is twice as slow as it usually is
    minimum-ms: 25           # but never below this
    maximum-ms: 100          # and always above this
    baseline-minutes: 60
```

The baseline is the **median** tick time of the last hour, not the average, so a few bad minutes
cannot teach the server that bad is normal. Samples taken during an incident are not counted at
all, for the same reason.

Both ends are clamped, and both clamps matter. Without the floor, a very quick server would alert
on ordinary jitter. Without the ceiling, a permanently broken server would quietly learn that
300 ms is fine and stop complaining altogether.

`/lagwatch status` shows what it has settled on, and says so while it is still learning.

## Discord alerts

You need a webhook. A webhook is just a link that lets the plugin post to one of your channels.

1. In Discord, right-click your channel and pick **Edit Channel**.
2. Go to **Integrations**, then **Webhooks**, then **New Webhook**.
3. Click **Copy Webhook URL**.
4. Paste it into `config.yml` under `webhook-url`, between the quotes.
5. Type `/lagwatch reload`, then `/lagwatch report discord` to test it.

A message should appear in your channel within a second. If nothing shows up, look at the server
console - the plugin writes down what Discord answered.

**Keep that link private.** Anyone who has it can post in your channel.

## The web page

Set `enabled: true` under `dashboard` and restart. The console will print a link:

```
[TickSentry] Web panel: http://127.0.0.1:8080/?token=a15e51d7...
```

Open it in a browser. You get TPS, tick time, the number of players, a chart of the last hour, and
a table of past incidents. It updates itself every 2 seconds.

**You do not have to host anything.** The page runs inside the plugin, on the same computer as
your server. There is no website to sign up for and nothing to pay.

### Opening it from another computer

By default only the computer running the server can open the page. This is on purpose: the
connection is not encrypted, so the token could be stolen on the way.

The easy safe way is an SSH tunnel. Run this on your own computer:

```
ssh -L 8080:127.0.0.1:8080 user@your-server
```

Then open `http://127.0.0.1:8080` as usual. If you know how to set up nginx or Caddy with HTTPS,
that works too.

Changing `bind` to `0.0.0.0` opens the page to everyone on the network **without encryption**.
The plugin warns you in the console if you do this.

### Prometheus and Grafana

The same web server also answers `/metrics` in the Prometheus format, so you can keep months of
history, chart TPS next to your machine's CPU and memory, and get paged at three in the morning
without leaving a browser tab open.

```yaml
scrape_configs:
  - job_name: minecraft
    static_configs:
      - targets: ['127.0.0.1:8080']
    params:
      token: ['your-token-from-config-yml']
```

If you would rather not put the token in the URL, send it as a header instead - the endpoint
accepts `X-Auth-Token` exactly like the panel does.

What you get:

| Metric | Meaning |
| --- | --- |
| `ticksentry_tps` | ticks per second, out of 20 |
| `ticksentry_mspt_milliseconds` | average tick time |
| `ticksentry_mspt_peak_milliseconds` | longest freeze in the window |
| `ticksentry_players` | players online |
| `ticksentry_heap_used_bytes` / `_max_bytes` | memory |
| `ticksentry_gc_collections` / `_milliseconds` | garbage collector activity |
| `ticksentry_loaded_chunks` | chunks loaded across all worlds |
| `ticksentry_incidents_24h` | incidents in the last day |
| `ticksentry_incident_active` | 1 while the server is lagging right now |
| `ticksentry_repeat_offender_chunks` | how many chunks keep coming back |
| `ticksentry_plugin_handler_seconds{plugin="..."}` | event handler time, per plugin |

Everything is a gauge. A Prometheus counter must never decrease, and none of these numbers can
promise that across a restart. Only the ten most expensive plugins get their own series - every
distinct label value costs storage, and a server with eighty plugins would quietly multiply it.

Set `metrics: false` under `dashboard` to turn the endpoint off and keep only the page.

## Update checks and statistics

```yaml
updates:
  check: true                # ask GitHub at startup whether a newer version exists
  bstats: true               # send anonymous usage statistics
```

The update check reads the GitHub releases page once at startup. **Nothing is ever downloaded or
installed** - it tells you in the console, tells an admin who joins, and you decide.

The statistics go to [bStats](https://bstats.org): server software and version, Java version,
operating system, core count, player count, and which TickSentry features are switched on. No
addresses, no names, nothing about your world. It exists to answer "which Minecraft versions
still need supporting", which is otherwise guesswork.

The server-wide switch in `plugins/bStats/config.yml` is honoured as well - turning bStats off
there turns it off here, whatever this file says. There is no bundled bStats library; the plugin
sends the payload itself, so the jar stays exactly as small as it was.

> **For whoever maintains this fork:** statistics are inert until the plugin is registered at
> bstats.org and the id it gives you is put into `BStatsReporter.SERVICE_ID`. Until then nothing
> is sent, whatever the config says - that is deliberate, so a fork cannot accidentally post to
> somebody else's page.

## Placeholders

These work if you have PlaceholderAPI installed:

| Placeholder | Shows |
| --- | --- |
| `%ticksentry_tps%` | TPS, for example `19.9` |
| `%ticksentry_mspt%` | tick time in ms |
| `%ticksentry_peak_ms%` | the slowest tick recently |
| `%ticksentry_status%` | `OK` or `LAG` |
| `%ticksentry_monitoring%` | `running` or `stopped` |
| `%ticksentry_last_category%` | what caused the last incident |
| `%ticksentry_incidents_24h%` | incidents in the last day |

## Whose farm is it

Coordinates only tell you where to walk. On a server with players the question is whose build it
is, so every chunk in a report gets an owner attached when one can be worked out:

```
 - world @ 1608, 1608 (entities: 1292, block entities: 0)
   region "ironfarm" (Steve); last player there: Notch, 4 min ago
```

Two sources feed this. If **WorldGuard**, **GriefPrevention** or **Towny** is installed, it is
asked who owns the ground - that is the real answer. If none of them is, TickSentry falls back
to who was last seen standing in that chunk, which is a hint rather than proof but works on a
server with no claim plugin at all. Visits older than a day are ignored.

None of the three is a dependency. The hooks are reflective, so a missing plugin costs nothing
and a plugin that changes its API costs one line of extra detail, never an error. Lookups run
only for the handful of chunks that made it into a report.

## The same chunk, over and over

One alert cannot tell a farm somebody built ten minutes ago from the farm that has been dragging
your server down every evening for a fortnight. The incidents are already in the database, so
TickSentry looks:

```
 - world @ 1608, 1608 (entities: 1292, block entities: 0)
   repeat offender: behind 7 of the last 12 incidents (worst 240 ms)
```

`/lagwatch offenders` shows the whole ranking, and takes a number of days like `stats` does. A
chunk named by several incidents in a row is where your evening goes - fixing that one place is
worth more than reacting to ten alerts.

Only automatic incidents count. Running `/lagwatch report` five times while testing something
would otherwise turn whatever chunk happened to be busiest into a "chronic problem".

## Finding the plugin that is at fault

Not every slowdown comes from the world. Sometimes one plugin simply does too much work in an
event handler, and no amount of counting mobs will ever show it.

TickSentry measures this directly. Bukkit keeps every listener in a list, so the plugin swaps
each one for a thin wrapper that times the original and passes the event straight through -
same order, same behaviour, two timestamp reads on top. Alerts can then say:

```
Plugin: SomePlugin used 61% of the last 30 s of server time
        (18400 ms, mostly in PlayerMoveEvent, 92413 handler calls).
Suggestion: Look at SomePlugin first: update it, check its settings, or disable it
            for a moment to confirm. Counting mobs will not help here.
```

A plugin that takes at least half of the window outranks whatever the chunk scan found - the
server thread really was sitting inside its code. Below that, the chunk verdict wins and the
plugin is only mentioned alongside it.

Type `/lagwatch plugins` at any time to see the ranking without waiting for an incident. The
same command also lists how many scheduler tasks each plugin has queued; Bukkit gives no way to
time those from the outside, so that part is a count, not a measurement.

Turn the whole thing off with `profiler.enabled: false` if you would rather TickSentry touched
nothing but its own listeners.

## Letting it clean up (off by default)

Everything above only looks. If you want TickSentry to act, it can sweep dropped items and thin
out mob pile-ups after an incident - but read this first, because it is the one part of the
plugin that deletes things your players own, and no restart undoes it.

```yaml
remediation:
  enabled: false             # nothing happens until you change this
  dry-run: true              # and it still only reports until you change this too
  warning-seconds: 30        # players in that world are told first
  cooldown-seconds: 600
  clear-items:
    enabled: true
    threshold: 300           # dropped items in one chunk
  cap-mobs:
    enabled: false           # off even when remediation is on
    threshold: 300           # mobs of one type in one chunk
    keep: 50                 # the farm keeps working, it just stops growing
    protected-types: [VILLAGER, IRON_GOLEM, ...]
```

Two switches, on purpose. Turning `enabled` on gets you dry-run: the plugin says what it would
have done and changes nothing. Watch that for a few days, adjust the thresholds, and only then
set `dry-run: false`.

These are never removed, whatever the numbers say: **named mobs, tamed pets, leashed animals,
anything riding or being ridden, renamed items**, and any type in `protected-types` (the default
list covers villagers, golems, horses, pets, item frames, minecarts and boats).

The plan is made from the scan, but never trusted. The world is read again at the moment of
removal, thirty seconds later, and only what is still there goes. If the chunk unloaded or
somebody already cleaned up, nothing happens. Whatever does happen is written to the console,
told to your admins in game, and sent to Discord.

## What it cannot do

TickSentry looks at **what is inside your world** and at **what your plugins do with the tick**.
It finds too many mobs, too many items, too many hoppers, crowds of players, and plugins whose
handlers run long.

It also watches memory, so it can point at the garbage collector or a full heap:

```
Memory: The garbage collector used 24% of the last 5 s (8 collections).
        The server was frozen for that time. Memory: 3900 MB of 4096 MB (95%).
        The heap is nearly full, so give the server more RAM (-Xmx).
```

It **cannot** see inside a plugin - it tells you which one is slow, not which line. Nor can it
account for time the server spends saving the world or generating new land. In those cases it
says "No obvious source" and suggests you run [spark](https://spark.lucko.me/), which digs
deeper.

If spark is installed, TickSentry adds spark's own numbers to its alerts.

The plugin also guesses how expensive things are. A hopper counts for more than a chest, a
villager for more than a dropped item. These are sensible guesses, not measurements. If alerts
feel too sensitive or too quiet on your server, change `mspt-threshold-ms` first.

## Trying it out

There is a full walkthrough in [TESTING.md](TESTING.md). The short version:

1. Set `sustained-seconds: 3` and run `/lagwatch reload`.
2. Stand somewhere and spawn a cow: `/summon minecraft:cow ~ ~ ~`
3. Run this about 11 times - it doubles the cows each time:
   ```
   /execute as @e[type=cow,distance=..10] at @s run summon minecraft:cow ~ ~ ~
   ```
4. Wait a few seconds. The alert should point at the exact chunk you are standing in.
5. Clean up with `/kill @e[type=minecraft:cow]` and `/kill @e[type=minecraft:item]`.

Put `sustained-seconds` back to 10 when you are done.

## Building it yourself

```bash
./gradlew build
```

You need JDK 21 to build, but the finished plugin runs on Java 11. The jar appears in
`build/libs/`.

The plugin adds nothing to your server except itself. The one library it uses (SQLite) is
downloaded by Paper on the first start, so the jar stays small.

## For developers

The code is split so that the interesting part can be tested without starting a server:

- `monitor/` - measuring ticks and scanning chunks. `HotspotAnalyzer` makes all the decisions
  (which chunk is worst, what the cause is) and knows nothing about Minecraft, so plain unit tests
  cover it. `PluginProfiler` is the one class here that has to touch Bukkit internals: it swaps
  registered listeners for timing wrappers. The ranking and the thresholds it feeds live in
  `PluginReport`, which is pure and tested.
- `discord/` - building and sending the alert. Sending happens on its own thread, so the server
  never waits for the network.
- `storage/` - saving incidents to SQLite. All database work happens off the main thread.
- `web/` - the dashboard. The web code never touches the Minecraft API; it reads snapshots that
  the main thread prepares for it.
- `commands/`, `config/`, `placeholders/`, `util/` - the rest.

Two things are worth knowing if you change the code. Reading chunks has to happen on the main
thread, so the scan is spread over several ticks with a 3 ms budget each - otherwise the plugin
would cause the very lag it looks for. And anything slow (network, database) must stay off the
main thread.

Run the tests with `./gradlew test`. There are 138 of them and none need a fake server.

Every push runs the same build on GitHub Actions, which also checks that the jar is still Java 11
bytecode - so nobody can break 1.16 support by accident.

## Licence

MIT. Do what you like with it - see [LICENSE](LICENSE).
