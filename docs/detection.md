# How it finds the cause

TickSentry checks how long each tick takes. One slow tick is normal, so it only reacts when the
server stays slow for `sustained-seconds` without a break. Then it looks for the reason in three
places: the world, the plugins, and the memory.

## In the world

The scan walks every loaded chunk, scores it by what it is carrying, and picks the worst one. The
score decides which of these it reports:

| Cause | What it means |
| --- | --- |
| Mob farm | lots of entities of the same type in one place |
| Dropped items | hundreds of items or XP orbs lying on the ground |
| Redstone / hoppers | lots of hoppers, droppers or furnaces |
| Mob spawners | several spawners running side by side |
| Minecart contraption | a lot of minecarts, usually hopper carts under a farm |
| Chunk loading | new land is being generated or read from disk |
| Player crowd | many players inside a single chunk |
| Entity overload | a large mix of different entities at once |
| Memory / garbage collector | the server ran out of breathing room in RAM |
| Plugin | one plugin's event handlers are eating the tick |
| No obvious source | no chunk stands out clearly |

How much each object contributes is adjustable - see
[cost weights](configuration.md#tuning-what-counts-as-expensive).

## Whose farm is it

Coordinates only tell you where to walk. On a server with players the question is whose build it is,
so every chunk in a report gets an owner attached when one can be worked out:

```
 - world @ 1608, 1608 (entities: 1292, block entities: 0)
   region "ironfarm" (Steve); last player there: Notch, 4 min ago
```

Two sources feed this. If **WorldGuard**, **GriefPrevention** or **Towny** is installed, it is asked
who owns the ground - that is the real answer. If none of them is, TickSentry falls back to who was
last seen standing in that chunk, which is a hint rather than proof but works on a server with no
claim plugin at all. Visits older than a day are ignored.

None of the three is a dependency. The hooks are reflective, so a missing plugin costs nothing and a
plugin that changes its API costs one line of extra detail, never an error. Lookups run only for the
handful of chunks that made it into a report.

## The same chunk, over and over

One alert cannot tell a farm somebody built ten minutes ago from the farm that has been dragging
your server down every evening for a fortnight. The incidents are already in the database, so
TickSentry looks:

```
 - world @ 1608, 1608 (entities: 1292, block entities: 0)
   repeat offender: behind 7 of the last 12 incidents (worst 240 ms)
```

`/lagwatch offenders` shows the whole ranking. A chunk named by several incidents in a row is where
your evening goes - fixing that one place is worth more than reacting to ten alerts.

Only automatic incidents count. Running `/lagwatch report` five times while testing something would
otherwise turn whatever chunk happened to be busiest into a "chronic problem".

## Finding the plugin that is at fault

Not every slowdown comes from the world. Sometimes one plugin simply does too much work in an event
handler, and no amount of counting mobs will ever show it.

TickSentry measures this directly. Bukkit keeps every listener in a list, so the plugin swaps each
one for a thin wrapper that times the original and passes the event straight through - same order,
same behaviour, two timestamp reads on top. Alerts can then say:

```
Plugin: SomePlugin used 61% of the last 30 s of server time
        (18400 ms, mostly in PlayerMoveEvent, 92413 handler calls).
Suggestion: Look at SomePlugin first: update it, check its settings, or disable it
            for a moment to confirm. Counting mobs will not help here.
```

A plugin that takes at least half of the window outranks whatever the chunk scan found - the server
thread really was sitting inside its code. Below that, the chunk verdict wins and the plugin is only
mentioned alongside it.

Run `/lagwatch plugins` at any time to see the ranking without waiting for an incident. The same
command also lists how many scheduler tasks each plugin has queued; Bukkit gives no way to time
those from the outside, so that part is a count, not a measurement.

Turn the whole thing off with `profiler.enabled: false` if you would rather TickSentry touched
nothing but its own listeners.

### What changed since last week

A profiler tells you what is expensive **right now**. It has no memory, so it cannot tell an
expensive plugin apart from a plugin that has *become* expensive - and the second one is the
actionable case, because something changed and the change is usually an update.

TickSentry writes down what each plugin costs every ten minutes, so it can answer that:

```
Costing more than usual:
 - Essentials now takes 3.2x the tick time it usually does (2.6% of the window,
   against 0.8% across 14 earlier readings at a similar player count).
```

It shows up in `/lagwatch plugins` and in the console at the moment of an incident.

The phrase "at a similar player count" is doing real work. Handler time scales with events and
events scale with players, so a reading taken at forty players will always look worse than the same
plugin at five. Only comparable readings are compared, and with fewer than eight of them it says
nothing at all - a "3x increase" over two quiet samples is noise, and a detector that cries wolf
gets turned off.

It also stays quiet about a plugin going from 0.01% to 0.05% of a tick. That has quintupled and
still costs nothing.

The history lives in the same database as the incidents and is deleted on the same `keep-days`
schedule. With `storage.enabled: false` it lives in memory and starts over on each restart, which
means it needs a few hours of uptime before it can say anything.

## Memory and the garbage collector

Some freezes have nothing to do with either the world or a plugin:

```
Memory: The garbage collector used 24% of the last 5 s (8 collections).
        The server was frozen for that time. Memory: 3900 MB of 4096 MB (95%).
        The heap is nearly full, so give the server more RAM (-Xmx).
```

Counting mobs would never show this, which is why it is measured separately.

## What it cannot do

It **cannot** see inside a plugin - it tells you which one is slow, not which line. Nor can it
account for time the server spends saving the world or generating new land. In those cases it says
"No obvious source" and suggests you run [spark](https://spark.lucko.me/), which digs deeper. If
spark is installed, TickSentry adds spark's own numbers to its alerts.

The plugin also guesses how expensive things are. A hopper counts for more than a chest, a villager
for more than a dropped item. These are sensible guesses, not measurements. If alerts feel too
sensitive or too quiet on your server, change `mspt-threshold-ms` first.
