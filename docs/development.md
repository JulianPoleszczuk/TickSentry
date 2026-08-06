# Development

## Building

```bash
./gradlew build
```

You need JDK 21 to build, but the finished plugin runs on Java 11. The jar appears in `build/libs/`.

The plugin adds nothing to your server except itself. The one library it uses (SQLite) is downloaded
by Paper on the first start, so the jar stays small.

## Project layout

The code is split so that the interesting part can be tested without starting a server:

| Package | What is in it |
| --- | --- |
| `monitor/` | measuring ticks and scanning chunks |
| `alert/` | the alert payload, the sinks (webhook, commands) and the public event |
| `discord/` | building and sending the Discord embed |
| `storage/` | saving incidents to SQLite |
| `web/` | the dashboard and the metrics endpoint |
| `remedy/` | the automatic clean-up |
| `commands/`, `config/`, `placeholders/`, `util/` | the rest |

`HotspotAnalyzer` makes all the decisions (which chunk is worst, what the cause is) and knows nothing
about Minecraft, so plain unit tests cover it. `PluginProfiler` is the one class that has to touch
Bukkit internals: it swaps registered listeners for timing wrappers. The ranking and the thresholds
it feeds live in `PluginReport`, which is pure and tested.

Sending happens on its own thread, so the server never waits for the network. All database work
happens off the main thread. The web code never touches the Minecraft API; it reads snapshots that
the main thread prepares for it.

Two things are worth knowing if you change the code. Reading chunks has to happen on the main
thread, so the scan is spread over several ticks with a 3 ms budget each - otherwise the plugin
would cause the very lag it looks for. And anything slow (network, database) must stay off the main
thread.

## Tests

```bash
./gradlew test
```

There are 283 of them, and none needs a running server.

Most cover the pure decision-making. Three files cover the parts that have to touch Bukkit, because
those are the ones that can damage somebody else's server - or, in the monitor's case, quietly fail
to notice the thing the whole plugin exists to notice:

- `PluginProfilerTest` swaps listeners on a real `HandlerList` and checks that priority, listener and
  the ignore-cancelled flag survive, that the delegate still receives the event, and that
  uninstalling puts the original object back.
- `RemedySafetyTest` checks every rule that decides what the automatic clean-up refuses to delete -
  players, named mobs, tamed pets, leashed animals, anything riding or being ridden.
- `TickMonitorTest` walks the detection state machine: a breach that ends a second early, an alert
  held back by the cooldown, a breather too short to count as recovery. The monitor reads the clock
  through a supplier, so the test cranks time forward by hand instead of sleeping.

None uses MockBukkit. `HandlerList` and `RegisteredListener` are ordinary Java objects, and both
Bukkit entities and `Server` are interfaces, so a proxy is enough - and unlike a mock server it
cannot quietly answer something the real API would not.

Every push runs the same build on GitHub Actions, which also checks that the jar is still Java 11
bytecode, so nobody can break 1.16 support by accident.

For a manual end-to-end run against a real server, see [Testing it yourself](testing.md).
