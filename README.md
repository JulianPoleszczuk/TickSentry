# TickSentry

Plugin do Minecraft Paper, ktory w tle pilnuje wydajnosci serwera, sam zgaduje, **gdzie** siedzi
przyczyna lagu, i wysyla o tym czytelny alert na Discorda.

Przewaga nad spark to nie dokladnosc, tylko wygoda: nikt nie musi niczego odpalac ani czytac
profilera. Alert przychodzi sam, z gotowa komenda do wklejenia.

## Co potrafi

- Mierzy czas ticku (MSPT) i TPS co tick, na sredniej kroczacej - pojedynczy skok nie wywoluje alarmu.
- Po nieprzerwanym przekroczeniu progu skanuje wszystkie zaladowane chunki i wskazuje top 5 podejrzanych.
- Zgaduje przyczyne: farma mobow, zalegajace przedmioty, redstone/hoppery, skupisko graczy, ogolne przeciazenie encjami.
- Wysyla embed na Discorda z koordynatami, przyczyna i sugerowana akcja - jezykiem admina, nie profilera.
- Cooldown miedzy alertami (domyslnie 5 min), zeby nie zaspamowac kanalu.
- Jesli na serwerze jest **spark**, dokleja jego statystyki (srednia i 95. percentyl czasu ticku).

## Wymagania

- Paper 1.20.6 lub nowszy (plugin kompilowany przeciw API 1.20.6, `api-version: 1.20`)
- Java 21

## Budowanie

```bash
./gradlew build
```

Gotowy plik: `build/libs/TickSentry-1.0.0.jar` - wrzuc go do `plugins/` i zrestartuj serwer.

## Konfiguracja

Po pierwszym starcie powstanie `plugins/TickSentry/config.yml`:

```yaml
monitor:
  mspt-threshold-ms: 50      # powyzej tylu ms serwer uznajemy za przeciazony
  sustained-seconds: 10      # jak dlugo prog musi sie utrzymac, zanim poleci alert
  scan-cooldown-seconds: 300 # minimalna przerwa miedzy alertami
  rolling-average-ticks: 100 # okno sredniej kroczacej
discord:
  enabled: true
  webhook-url: ""
  mention-role-id: ""        # opcjonalne ID roli do oznaczenia (same cyfry)
scan:
  ignored-worlds: []
  top-chunks-count: 5
```

Webhook zdobywasz tak: ustawienia kanalu na Discordzie -> Integracje -> Webhooki -> Utworz webhook -> kopiuj URL.

## Komendy

Wszystkie wymagaja uprawnienia `ticksentry.admin` (domyslnie: OP).

| Komenda | Opis |
| --- | --- |
| `/lagwatch status` | biezacy TPS, czas ticku, stan monitoringu i cooldownu |
| `/lagwatch report` | wymusza skan i wypisuje wynik w czacie |
| `/lagwatch report discord` | to samo, ale wysyla tez na webhook (dobre do sprawdzenia konfiguracji) |
| `/lagwatch history` | ostatnie incydenty z tej sesji serwera |
| `/lagwatch reload` | przeladowuje `config.yml` |

Aliasy: `/ts`, `/ticksentry`.

## Jak to sprawdzic u siebie

1. Wrzuc jar na testowy serwer Paper, ustaw `webhook-url` i zrob `/lagwatch reload`.
2. `/lagwatch report discord` - jesli embed pojawi sie na kanale, konfiguracja jest dobra.
3. Zrob prawdziwy lag: stan w jednym miejscu i wywolaj
   `/execute run summon cow ~ ~ ~` w petli albo `/summon` przez komendy blokowe - okolo 2000 encji
   w jednym chunku wystarczy, zeby MSPT przekroczylo 50 ms.
4. Po `sustained-seconds` sekundach alert powinien wskazac dokladnie ten chunk z kategoria "Farma mobow".

Zeby nie czekac 10 sekund w kolko, na czas testow warto ustawic `sustained-seconds: 3`
i `scan-cooldown-seconds: 10`.

## Ograniczenia (swiadome, MVP)

- Nie ma samplowania stosu wywolan jak w spark. Plugin koreluje wysoki MSPT z anomalna zawartoscia
  chunkow, wiec **nie wykryje** lagu pochodzacego z pluginu, zapisu mapy czy generowania terenu -
  w takim przypadku raportuje kategorie "Nieoczywiste zrodlo" i sugeruje odpalenie sparka.
- Wagi kosztu encji i block-entity (`HotspotAnalyzer`) sa przyblizeniem, nie wynikiem profilowania.
  Sa tak dobrane, zeby 40 hopperow wazylo wiecej niz 40 skrzyn, a 200 itemow mniej niz 200 villagerow.
- Historia incydentow zyje w pamieci i znika po restarcie serwera.

## Struktura

```
com/../ticksentry
├── TickSentryPlugin        spiecie calosci
├── monitor/
│   ├── TickMonitor         pomiar MSPT/TPS co tick i detekcja trwalego przekroczenia
│   ├── ChunkHotspotScanner odczyt chunkow z Bukkita (glowny watek)
│   ├── ChunkStat           migawka chunka, bez zaleznosci od Bukkita
│   ├── HotspotAnalyzer     wagi, ranking i kategoryzacja - cala logika, w pelni testowalna
│   ├── LagCategory         kategorie przyczyn
│   ├── LagEvent            incydent
│   └── SparkBridge         opcjonalne dane ze sparka (refleksja)
├── discord/
│   ├── DiscordWebhookClient wysylka async na osobnym watku
│   └── EmbedBuilder         JSON embeda bez zadnej biblioteki
├── commands/LagWatchCommand
├── config/ConfigManager
└── storage/AlertHistory     bufor w pamieci (faza 2: trwale skladowanie)
```

Testy jednostkowe (`./gradlew test`) pokrywaja logike oceny chunkow i budowanie JSON-a embeda -
19 testow, bez mockowania Bukkita.
