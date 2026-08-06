# Configuration

The file is `plugins/TickSentry/config.yml`. After changing it, run `/lagwatch reload`.

## The whole file at a glance

```yaml
monitor:
  mspt-threshold-ms: 50      # a tick slower than this counts as a problem
  sustained-seconds: 10      # how long the problem must last before you get an alert
  scan-cooldown-seconds: 300 # wait this long before sending another alert
  rolling-average-ticks: 100 # how many ticks are averaged together
  trigger-on: average        # average, p95 or p99
  recovery-alert: true       # send a message when the server is fine again
  recovery-seconds: 15       # how long it must stay fine before that message
  in-game-alerts: true       # also tell admins who are online
  adaptive-threshold:
    enabled: false           # learn what "normal" is on this server

discord:
  enabled: true
  webhook-url: ""            # paste your webhook link here
  mention-role-id: ""        # optional: a role to ping, digits only

webhook:                     # any other JSON endpoint - Slack, n8n, your own script
  enabled: false
  url: ""

commands:                    # run console commands instead
  enabled: false
  on-incident: []
  on-recovery: []

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
  enabled: false             # let the plugin remove things?
  dry-run: true              # and even then, only report at first

dashboard:
  enabled: false             # the web page
  bind: "127.0.0.1"          # only this computer can open it
  port: 8080
  token: ""                  # leave empty, the plugin fills it in
  metrics: true              # also serve /metrics for Prometheus

updates:
  check: true                # ask GitHub at startup whether a newer version exists
  bstats: true               # send anonymous usage statistics

weights:                     # optional
  entities: {}
  block-entities: {}
```

The alert destinations (`discord`, `webhook`, `commands`) are covered in
[Alerts and integrations](alerts.md), the web panel in [Dashboard and metrics](dashboard.md), and
`remediation` in [Automatic clean-up](remediation.md).

## What the numbers mean

**Tick time** (also called MSPT) is how long the server needs to do one round of work. It should
finish within 50 ms. A healthy server takes about 5-25 ms. If it takes longer than 50 ms, the
server cannot keep up and players feel it.

**TPS** is the same thing seen from the other side: how many rounds fit into one second. 20 is
perfect. Below 18 people start noticing.

**p95 and p99** are the tick times your bad ticks reach. If the average is 20 ms but p99 is 400 ms,
one tick in a hundred is freezing the server for almost half a second - which players feel as
stuttering even though the average looks fine. An average can only tell you the server is
*generally* behind; these tell you it is occasionally stopping dead.

On Paper these come from the real duration of every individual tick. Spigot does not expose that -
all it offers is an average of its own - so there TickSentry says so in `/lagwatch status` rather
than reporting percentiles it cannot actually measure.

## Alerting on the bad ticks instead of the average

By default the alert fires on the average. If players report lag that your graphs deny, point the
threshold at a percentile instead:

```yaml
monitor:
  trigger-on: p95   # average (default), p95, or p99
```

`p95` is the one to reach for. `p99` on a 100-tick window is very nearly the worst single tick of
the last five seconds, so it will alert on a server that stalls every few seconds - a real problem,
but a much sharper instrument. It is left on `average` on purpose: switching a quiet server to
`p95` will start finding things, and that should be your decision rather than a surprise from an
update.

## A threshold that fits your server

A fixed 50 ms suits the average server and nobody else. A box that habitually runs at 45 ms gets an
alert for every hiccup until the admin gives up and turns alerts off. A box that runs at 8 ms can
quintuple its tick time - a real regression worth investigating - and never say a word.

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

## Tuning what counts as expensive

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

## Update checks and statistics

```yaml
updates:
  check: true
  bstats: true
```

The update check reads the GitHub releases page once at startup. **Nothing is ever downloaded or
installed** - it tells you in the console, tells an admin who joins, and you decide.

The statistics go to [bStats](https://bstats.org): server software and version, Java version,
operating system, core count, player count, and which TickSentry features are switched on. No
addresses, no names, nothing about your world. It exists to answer "which Minecraft versions still
need supporting", which is otherwise guesswork.

The server-wide switch in `plugins/bStats/config.yml` is honoured as well - turning bStats off
there turns it off here, whatever this file says. There is no bundled bStats library; the plugin
sends the payload itself, so the jar stays exactly as small as it was.

The first submission happens three minutes after startup and says in the console whether it worked.
After that it stays quiet and sends once every half hour.

> **If you fork this:** set `BStatsReporter.SERVICE_ID` back to `0`, or register your fork and use
> its own id. Otherwise your servers report into this plugin's page.
