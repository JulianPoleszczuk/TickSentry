# Compatibility

Minecraft **1.16.5 and anything newer**. You need **Java 11 or newer**.

| Minecraft | Java your server needs | TickSentry |
| --- | --- | --- |
| 1.16.5 | 8 or newer | works on Java 11+ |
| 1.17 | 16 or newer | works |
| 1.18 - 1.20.4 | 17 or newer | works |
| 1.20.5 and newer | 21 or newer | works |

From 1.17 onwards you do not have to think about Java at all, because Minecraft already asks for a
newer version than the plugin does. The only setup that will not work is a 1.16 server still running
Java 8.

Both ends were tested on real servers: Paper 1.16.5 on Java 11, and Paper 1.21.10 on Java 21.

## Server software

**Paper** and its forks are the recommended setup and give you everything.

**Spigot** works, but does not expose the duration of individual ticks - all it offers is an average
of its own. Percentile alerts (`trigger-on: p95` / `p99`) are therefore unavailable there, and
`/lagwatch status` says so rather than reporting numbers it cannot measure.

## Optional plugins

None of these is a dependency. Every hook is reflective, so a missing plugin costs nothing.

| Plugin | What it adds |
| --- | --- |
| WorldGuard, GriefPrevention, Towny | tells you whose land a suspicious chunk sits on |
| spark | its own profiling numbers are added to alerts |
| PlaceholderAPI | the `%ticksentry_...%` placeholders |

## Folia

**Folia runs in limited mode.** Folia ticks each region of the world on its own thread, so no single
thread may read the whole world. That rules out the chunk scan, and with it everything the scan
feeds: an alert there can name a plugin or memory, but never a chunk, a farm or an owner. Automatic
clean-up is off for the same reason.

Everything that never needed one main thread still works: memory and garbage collector watching,
per-plugin event handler timings, the incident history, the web panel, Discord and in-game alerts.
The startup log spells out which half you are getting, and `/lagwatch status` says so too.

Nothing about that mode is assumed from the name "Folia" - whether the server will report a tick
time is discovered by asking it. A fork that answers gets monitored; one that refuses is told so
rather than shown a comforting number nobody measured.

This mode is written against Folia's published scheduler API but has **not** been tested on a live
Folia server. If you run one, the startup log is the thing to read - and an issue report is welcome.
