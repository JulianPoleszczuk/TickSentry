# Alerts and integrations

Nothing about deciding to alert is Discord-specific, so there are four ways out. Use as many as you
like at once - `/lagwatch status` lists whichever are configured.

## Discord

You need a webhook. A webhook is just a link that lets the plugin post to one of your channels.

1. In Discord, right-click your channel and pick **Edit Channel**.
2. Go to **Integrations**, then **Webhooks**, then **New Webhook**.
3. Click **Copy Webhook URL**.
4. Paste it into `config.yml` under `webhook-url`, between the quotes.
5. Run `/lagwatch reload`, then `/lagwatch report discord` to test it.

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/..."
  mention-role-id: ""    # optional, digits only
```

A message should appear in your channel within a second. If nothing shows up, look at the server
console - the plugin writes down what Discord answered.

**Keep that link private.** Anyone who has it can post in your channel.

## A plain webhook

```yaml
webhook:
  enabled: true
  url: "https://hooks.slack.com/services/..."
  headers:
    Authorization: "Bearer abc123"   # optional
```

One JSON POST, which is all Slack, Mattermost, n8n, Home Assistant, a Zapier catch hook or a script
of your own needs:

```json
{"event":"incident",
 "text":"Server is lagging: Mob farm - TPS 18.3, tick time 80 ms at world @ 1608, 1608.",
 "cause":"MOB_FARM","tps":18.3,"mspt":80.1,"p99Ms":210.0,
 "world":"world","x":1608,"z":1608,"owner":"somebody's claim",
 "suggestion":"Go there (/tp 1608 ~ 1608). Suspected farm: 841x cow."}
```

Chat services render `text` and ignore the rest; a script reads the fields and ignores the text.
There is no retry on a failed delivery - unlike Discord, this endpoint is unknown, and it may be a
script that already acted on the alert.

## Console commands

```yaml
commands:
  enabled: true
  on-incident:
    - "say Staff have been notified about the lag"
    - "discordsrv broadcast Lag at {world} {x} {z}"
  on-recovery:
    - "say All clear - the server recovered after {duration} s"
```

Placeholders: `{cause}` `{tps}` `{mspt}` `{world}` `{x}` `{z}` `{duration}`.

This is the escape hatch. Whatever integration you want, you can usually reach it with a command
without waiting for it to be added here. These run **as the console and are unrestricted**, which is
why the section is off by default.

## In-game alerts

```yaml
monitor:
  in-game-alerts: true
```

Admins who are online get the warning in chat, with a button that teleports them to the problem.
Who sees it is decided by the `ticksentry.alerts` permission - see
[Commands and permissions](commands.md#permissions).

## An event, for plugin authors

```java
@EventHandler
public void onLag(TickSentryIncidentEvent event) {
    LagEvent incident = event.getIncident();
    getLogger().info("Cause: " + incident.category().title());
}
```

Fired on the server thread once the incident has been reported, carrying everything the plugin
measured and concluded. Not cancellable - it is a notification about something that already
happened. A listener that throws is logged and cannot break the alert that triggered it.

## Translating what players see

`plugins/TickSentry/messages.yml` holds the text that appears **in chat**: the lag warning, the
clean-up notice, the update notice, and the names of the causes.

```yaml
alert:
  lagging: "&c[TickSentry] &eSerwer muli: &b{cause}&7 przy &f{location}&8 (/tp {x} ~ {z})"
category:
  MOB_FARM:
    title: "Farma mobów"
    description: "Dużo tych samych stworów w jednym miejscu"
```

Use `&` for colours. Anything in `{braces}` is filled in by the plugin - keep the spelling, move it
wherever the sentence needs it. Run `/lagwatch reload` afterwards.

A key you delete falls back to the English copy inside the jar, so a partial translation is safe,
and a key added by a future update never leaves a blank line where a message used to be.

Everything a person reads is in there: the lag warning, every `/lagwatch` reply, the Discord embed,
the `%ticksentry_...%` placeholders, and the advice sentences ("Go there, suspected farm: 841x
cow").

Two details worth knowing:

- **Discord** gets the same keys with colour codes stripped, since it would otherwise print the
  section signs as literal characters. Discord's own `**bold**` works fine there.
- **The advice sentences are not listed with English values.** The English for those lives in the
  code, so `messages.yml` has no second copy of it to fall out of step. The `advice:` section is
  commented-out examples documenting each key and the placeholders it takes - uncomment one and
  write your own, or leave it and keep the built-in text.

**Still English:** the server console. That is deliberate - console lines are what people paste into
bug reports and search engines, and a translated stack trace helps nobody.
