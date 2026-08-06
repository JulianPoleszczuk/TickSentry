# Automatic clean-up (off by default)

Everything else in TickSentry only looks. If you want it to act, it can sweep dropped items and thin
out mob pile-ups after an incident - but read this first, because it is the one part of the plugin
that deletes things your players own, and no restart undoes it.

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

## Two switches, on purpose

Turning `enabled` on gets you dry-run: the plugin says what it would have done and changes nothing.
Watch that for a few days, adjust the thresholds, and only then set `dry-run: false`.

`cap-mobs` stays off even after that, because killing mobs is a bigger decision than sweeping
litter.

## What is never removed

Whatever the numbers say: **named mobs, tamed pets, leashed animals, anything riding or being
ridden, renamed items**, and any type in `protected-types`. The default list covers villagers,
wandering traders, golems, horses and llamas, pets, armour stands, item frames, paintings, minecarts
and boats.

## The plan is never trusted

The plan is made from the scan, but the world is read again at the moment of removal, thirty seconds
later, and only what is still there goes. If the chunk unloaded or somebody already cleaned up,
nothing happens.

Whatever does happen is written to the console, told to your admins in game, and sent to your alert
destinations.
