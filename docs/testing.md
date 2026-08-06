# Testing TickSentry

A step-by-step guide: from an empty server to having every feature verified.
The whole run takes about 15 minutes.

## The short version

1. Set `sustained-seconds: 3` and run `/lagwatch reload`.
2. Stand somewhere and spawn a cow: `/summon minecraft:cow ~ ~ ~`
3. Run this about 11 times - it doubles the cows each time:
   ```
   /execute as @e[type=cow,distance=..10] at @s run summon minecraft:cow ~ ~ ~
   ```
4. Wait a few seconds. The alert should point at the exact chunk you are standing in.
5. Clean up with `/kill @e[type=minecraft:cow]` and `/kill @e[type=minecraft:item]`.

Put `sustained-seconds` back to 10 when you are done. The rest of this page is the same run with
every feature checked along the way.

## Before you start

You need a Paper 1.16.5+ server running on Java 11 or newer.

1. Build the plugin: `./gradlew build`
2. Copy `build/libs/TickSentry-1.0.0.jar` into the server's `plugins/` directory
3. Start the server once and stop it - this creates `plugins/TickSentry/config.yml`

## Settings for the test run

By default an alert fires after 10 seconds of overload, and the next one only after 5 minutes.
That is too slow for testing. Change `plugins/TickSentry/config.yml` to:

```yaml
monitor:
  mspt-threshold-ms: 50
  sustained-seconds: 3       # instead of 10
  scan-cooldown-seconds: 15  # instead of 300
  recovery-seconds: 10       # instead of 15
dashboard:
  enabled: true              # web panel for the test
```

**Restore the defaults once you are done** - otherwise the first real lag will alert you every
15 seconds.

## Step 1: did the plugin start at all

Start the server and look for these lines in the console:

```
[TickSentry] TickSentry active - threshold 50.0 ms for 3 s. History: SQLite (incidents.db).
[TickSentry] Web panel: http://127.0.0.1:8080/?token=...
```

If you see `memory` instead of `SQLite`, the database driver was not downloaded - check that the
server had internet access on its first start.

The `Spark detected` line only appears when spark is present (Paper bundles it).

## Step 2: the basic reading

In game or in the server console:

```
/lagwatch status
```

You should see TPS around 20, tick time somewhere between 1 and 20 ms, and `Monitoring: running`.

## Step 3: create real lag

Join the game, walk away from spawn and stand still. Start with one cow:

```
/summon minecraft:cow ~ ~ ~
```

Then the command that **doubles** the cow count. Every use means twice as many entities:

```
/execute as @e[type=cow,distance=..10] at @s run summon minecraft:cow ~ ~ ~
```

Run it around 11 times: 2, 4, 8, 16 ... 2048 cows. From roughly a thousand the server visibly
slows down - which is the point.

> Testing from the console instead of in game? Add coordinates:
> `/execute positioned 100 70 100 run summon minecraft:cow 100 70 100`,
> and hold the chunk open first with `/forceload add 100 100`.

## Step 4: what should happen

After roughly 3 seconds of overload, the console prints:

```
[TickSentry] Sustained lag detected: MSPT 80.1 ms, TPS 18.29. Cause: Mob farm.
[TickSentry]  - world @ 1608, 1608 (entities: 1292, block entities: 0)
[TickSentry] Suggestion: Go there (/tp 1608 ~ 1608). Suspected farm: 841x cow.
              Quick fix: /kill @e[type=cow,x=1600,y=-64,z=1600,dx=16,dy=384,dz=16]
```

Check that the reported coordinates are the place you are actually standing in. This is the single
most important test of the whole plugin - whether it points at the right chunk.

Meanwhile:

- `/lagwatch status` shows a tick time above the threshold
- `/lagwatch report` lists the five most suspicious chunks
- on the web panel the tick time turns red and the chart crosses the threshold line

## Step 5: cleanup and recovery

```
/kill @e[type=minecraft:cow]
/kill @e[type=minecraft:item]
```

The second command matters: killing the cows leaves several thousand dropped items behind, which
lag on their own. Before you remove them you will see the plugin **change the reported cause** to
"Dropped items" - a good test of the categorisation.

After about 10 seconds of calm:

```
[TickSentry] Server is back to normal after 21 s.
```

## Step 6: history and statistics

```
/lagwatch history
/lagwatch stats 7
```

`history` lists incidents with time and place, `stats` gives a summary with the daily spread.
**Restart the server and run `/lagwatch history` again** - the entries must still be there. That
proves the SQLite storage works.

## Step 7: the web panel

Paste the address from the console (token included) into a browser:

```
http://127.0.0.1:8080/?token=your-token
```

While you are there, confirm the access control works - the same address **without** `?token=...`
must return `401`.

The panel refreshes itself every 2 seconds. If the chart looks empty, wait a moment: samples are
taken every 5 seconds.

## Step 8: the Discord alert

1. Discord: channel → **Edit Channel** → **Integrations** → **Webhooks** → **New Webhook**
2. **Copy Webhook URL**
3. In `config.yml`:
   ```yaml
   discord:
     enabled: true
     webhook-url: "https://discord.com/api/webhooks/..."
   ```
4. `/lagwatch reload`
5. `/lagwatch report discord` - the embed should show up in the channel within a second

If nothing arrives, check the console - the plugin logs the response code Discord returned.

A webhook URL works like a password: whoever holds it can post to your channel. Keep it out of
repositories and screenshots.

## Step 9: restore the settings

Go back to `sustained-seconds: 10`, `scan-cooldown-seconds: 300` and `recovery-seconds: 15`,
then run `/lagwatch reload`.

## What this test will not cover

This walkthrough only exercises the chunk scan. It says nothing about the plugin profiler or the
memory watcher, both of which react to things a pile of cows cannot produce - see
[How it finds the cause](detection.md) for what each of the three looks at, and for the limits that
are deliberate rather than bugs.
