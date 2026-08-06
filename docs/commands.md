# Commands and permissions

You need to be an operator (or have the `ticksentry.admin` permission). You can type `/ts` instead
of `/lagwatch`.

| Command | What it does |
| --- | --- |
| `/lagwatch status` | shows TPS and tick time right now |
| `/lagwatch report` | checks the server immediately and lists the 5 worst chunks |
| `/lagwatch report discord` | same, but also sends it to Discord (good for testing your webhook) |
| `/lagwatch plugins` | shows which plugins spent the most time in their event handlers |
| `/lagwatch worlds` | shows what each world is carrying, densest first |
| `/lagwatch history` | shows past incidents, even from before a restart |
| `/lagwatch offenders` | shows the chunks that keep causing incidents, not just the last one |
| `/lagwatch stats` | shows a summary: how many incidents, what caused them, at what time of day |
| `/lagwatch tp <world> <x> <z>` | teleports you to a reported spot (this is what the buttons run) |
| `/lagwatch reload` | loads the settings file again |

`/lagwatch stats` and `/lagwatch offenders` take a number of days, for example
`/lagwatch stats 30`. Without one, `stats` uses 7 days and `offenders` uses `storage.offender-days`.

`/lagwatch report discord` also starts the alert cooldown, so an automatic alert cannot post the
same incident to the channel moments after you sent it there by hand.

## Permissions

| Permission | Default | What it grants |
| --- | --- | --- |
| `ticksentry.admin` | op | all `/lagwatch` commands |
| `ticksentry.alerts` | op | lag warnings in the game chat, and nothing else |
| `ticksentry.teleport` | op | the `[TP]` buttons in reports |

`ticksentry.alerts` is handy if you want moderators warned without giving them the commands.

Treat `ticksentry.teleport` as a **teleport** permission rather than a reporting one: it lets
whoever holds it reach any coordinates in any loaded world. If you want moderators to read reports
without that, grant `ticksentry.admin` and revoke `ticksentry.teleport`.

## Clicking through to the problem

Every line with coordinates - in `report`, `history` and `offenders` - ends in a `[TP]` button that
takes you there:

```
 1. world @ 1608, 1608 - 1292 entities, 0 block entities (mostly 841x cow) [TP]
```

The plugin already knows where the problem is, so reading the numbers out and typing them again was
work nobody needed to do. Console, and anyone without the permission, get the same line without the
button.

## Which world is filling up

Everything else the plugin reports is server-wide, and "4,000 entities" cannot tell you whether that
is spread evenly or all sitting in one nether hub. `/lagwatch worlds` breaks it down:

```
 1. world_nether - 2250 entities in 150 chunks (15.0/chunk, 43% of all, 0 players)
 2. world - 3000 entities in 2000 chunks (1.5/chunk, 57% of all, 12 players)
```

Ordered by entities **per chunk**, not by total. Sorting by total would name the overworld every
time, because that is where the players are; density is what tells a big world apart from a crowded
one - and the nether above is the one somebody built a farm in, despite holding fewer entities.

The same numbers are exported per world to Prometheus, so a Grafana panel can show which world is
filling up rather than just that the server is.

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
