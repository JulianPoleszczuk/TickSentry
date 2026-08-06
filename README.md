<div align="center">

# TickSentry

**A Minecraft server monitor that tells you _what_ is causing the lag.**

[![Build](https://github.com/JulianPoleszczuk/TickSentry/actions/workflows/build.yml/badge.svg)](https://github.com/JulianPoleszczuk/TickSentry/actions/workflows/build.yml)
[![Minecraft 1.16.5+](https://img.shields.io/badge/Minecraft-1.16.5%2B-brightgreen)](docs/compatibility.md)
[![Java 11+](https://img.shields.io/badge/Java-11%2B-orange)](docs/compatibility.md)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

</div>

---

When the server slows down, TickSentry checks every loaded chunk, finds the worst one, and sends
you a message with the coordinates and a command you can paste to fix it.

You do not have to run anything or watch anything. It works on its own.

```
Sustained lag detected: MSPT 80.1 ms, TPS 18.29. Cause: Mob farm.
 - world @ 1608, 1608 (entities: 1292, block entities: 0)
Suggestion: Go there (/tp 1608 ~ 1608). Suspected farm: 841x cow.
            Quick fix: /kill @e[type=cow,x=1600,y=-64,z=1600,dx=16,dy=384,dz=16]
```

## What it does

| | |
| --- | --- |
| **Names the cause** | A mob farm, dropped items, hoppers, a crowd of players, chunk loading, or plain entity overload. |
| **Names the owner** | Asks WorldGuard, GriefPrevention or Towny whose land it is, or falls back to who was last standing there. |
| **Blames the right plugin** | Times every other plugin's event handlers, so an alert can name a plugin instead of a chunk that did nothing wrong. |
| **Spots regressions** | Remembers what each plugin used to cost, so it can say one has *become* expensive - the question a live profiler cannot answer. |
| **Watches memory** | Tells you when a freeze came from the garbage collector or from running out of RAM, which counting mobs would never show. |
| **Alerts anywhere** | Discord, any JSON webhook, console commands, in-game messages, plus a recovery message when it is over. |
| **Keeps history** | Every incident is saved, so you can ask which chunks keep coming back and what time of day your server struggles. |
| **Charts and metrics** | A built-in web page and a `/metrics` endpoint for Prometheus and Grafana. |
| **Cleans up, if you let it** | Can sweep dropped items and thin out mob pile-ups, but only when you explicitly turn it on. By default it changes nothing. |

## Install

1. Download `TickSentry-1.0.0.jar` from [Releases](https://github.com/JulianPoleszczuk/TickSentry/releases), or build it yourself (below).
2. Drop it into your server's `plugins` folder.
3. Restart the server.

That is all. The plugin starts working right away and writes its settings to
`plugins/TickSentry/config.yml`. To get Discord alerts, paste a webhook URL into that file - see
[Alerts and integrations](docs/alerts.md).

## Commands

| Command | What it does |
| --- | --- |
| `/lagwatch status` | TPS and tick time right now |
| `/lagwatch report` | check immediately and list the 5 worst chunks |
| `/lagwatch plugins` | which plugins spend the most time in their event handlers |
| `/lagwatch history` | past incidents, even from before a restart |
| `/lagwatch offenders` | the chunks that keep causing incidents |

`/ts` works as a shorthand. The [full list](docs/commands.md) covers `worlds`, `stats`, `tp` and
`reload`, plus the permissions.

## Documentation

| Page | What is in it |
| --- | --- |
| [Configuration](docs/configuration.md) | Every setting, thresholds, cost weights, what TPS and MSPT actually mean |
| [Commands and permissions](docs/commands.md) | All commands, teleport buttons, PlaceholderAPI placeholders |
| [Alerts and integrations](docs/alerts.md) | Discord, webhooks, console commands, the developer API, translations |
| [Dashboard and metrics](docs/dashboard.md) | The web page, Prometheus, Grafana, health checks |
| [How it finds the cause](docs/detection.md) | Chunk scan, land ownership, repeat offenders, the plugin profiler, limits |
| [Automatic clean-up](docs/remediation.md) | The one part that deletes things, and every safety rule around it |
| [Compatibility](docs/compatibility.md) | Minecraft and Java versions, Folia support |
| [Testing it yourself](docs/testing.md) | A 15 minute walkthrough that produces real lag on purpose |
| [Development](docs/development.md) | Building, project layout, the test suite |

## Requirements

Minecraft **1.16.5 or newer** on **Java 11 or newer**. Paper is recommended: Spigot works, but
cannot report per-tick timings, so percentile alerts are unavailable there. Folia runs in
[limited mode](docs/compatibility.md#folia).

## Building

```bash
./gradlew build
```

You need JDK 21 to build, but the finished plugin runs on Java 11. The jar appears in
`build/libs/`. The plugin adds nothing to your server except itself: its one library (SQLite) is
downloaded by Paper on first start, so the jar stays small.

## Licence

MIT. Do what you like with it - see [LICENSE](LICENSE).
