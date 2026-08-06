# Dashboard and metrics

Set `enabled: true` under `dashboard` and restart. The console will print the address:

```
[TickSentry] Web panel: http://127.0.0.1:8080/ - open it with the token from config.yml (dashboard.token).
```

The token is **not** printed. Consoles get pasted into bug reports, and a log line carrying a
working access token is how somebody accidentally publishes one. Copy it out of `config.yml` and
open `http://127.0.0.1:8080/?token=your-token`.

You get TPS, tick time, the number of players, a chart of the last hour, and a table of past
incidents. It updates itself every 2 seconds.

**You do not have to host anything.** The page runs inside the plugin, on the same computer as your
server. There is no website to sign up for and nothing to pay.

```yaml
dashboard:
  enabled: true
  bind: "127.0.0.1"
  port: 8080
  token: ""        # leave empty, the plugin generates one on first start
  metrics: true
```

## Opening it from another computer

By default only the computer running the server can open the page. This is on purpose: the
connection is not encrypted, so the token could be stolen on the way.

The easy safe way is an SSH tunnel. Run this on your own computer:

```
ssh -L 8080:127.0.0.1:8080 user@your-server
```

Then open `http://127.0.0.1:8080` as usual. If you know how to set up nginx or Caddy with HTTPS,
that works too.

Changing `bind` to `0.0.0.0` opens the page to everyone on the network **without encryption**. The
plugin warns you in the console if you do this.

## Prometheus and Grafana

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

If you would rather not put the token in the URL, send it as a header instead - the endpoint accepts
`X-Auth-Token` exactly like the panel does.

What you get:

| Metric | Meaning |
| --- | --- |
| `ticksentry_tps` | ticks per second, out of 20 |
| `ticksentry_mspt_milliseconds` | average tick time |
| `ticksentry_mspt_p95_milliseconds` / `_p99_` | what the bad ticks reach |
| `ticksentry_mspt_peak_milliseconds` | longest freeze in the window |
| `ticksentry_players` | players online |
| `ticksentry_heap_used_bytes` / `_max_bytes` | memory |
| `ticksentry_gc_collections` / `_milliseconds` | garbage collector activity |
| `ticksentry_loaded_chunks` | chunks loaded across all worlds |
| `ticksentry_incidents_24h` | incidents in the last day |
| `ticksentry_incident_active` | 1 while the server is lagging right now |
| `ticksentry_repeat_offender_chunks` | how many chunks keep coming back |
| `ticksentry_world_entities{world="..."}` | entities, per world |
| `ticksentry_world_loaded_chunks{world="..."}` | loaded chunks, per world |
| `ticksentry_world_players{world="..."}` | players, per world |
| `ticksentry_plugin_handler_seconds{plugin="..."}` | event handler time, per plugin |

Everything is a gauge, percentiles included. A Prometheus counter must never decrease, and none of
these numbers can promise that across a restart; the percentiles are gauges rather than a summary
because they are computed over a window this plugin owns, and exporting them as a summary would
invite `quantile()` queries that an already-aggregated number cannot answer. Only the ten most
expensive plugins get their own series - every distinct label value costs storage, and a server with
eighty plugins would quietly multiply it.

Set `metrics: false` under `dashboard` to turn the endpoint off and keep only the page.

## Health checks

`/healthz` needs no token and answers one word, so an uptime monitor can watch it:

```
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/healthz
200
```

It returns 503 once the main thread stops taking measurements - a deadlock, a stop-the-world pause
that never ended, a crash part-way through shutdown. That is worth having separately from "does the
port answer", because this web server runs on its own threads and will keep answering cheerfully
long after the game itself has stopped.

Lag is not unhealthy here. A laggy server is still up, the alerts already cover it, and paging
somebody at three in the morning for a mob farm is not what an uptime monitor is for.
