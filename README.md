# TickSentry

Plugin do Minecraft Paper, ktory w tle pilnuje wydajnosci serwera, sam zgaduje, **gdzie** siedzi
przyczyna lagu, i wysyla o tym czytelny alert na Discorda.

Przewaga nad spark to nie dokladnosc, tylko wygoda: nikt nie musi niczego odpalac ani czytac
profilera. Alert przychodzi sam, z gotowa komenda do wklejenia.

## Co potrafi

- Mierzy czas ticku (MSPT) i TPS co tick, na sredniej kroczacej - pojedynczy skok nie wywoluje alarmu.
- Po nieprzerwanym przekroczeniu progu skanuje wszystkie zaladowane chunki i wskazuje top 5 podejrzanych.
  Skan jest rozlozony na kolejne ticki z budzetem 3 ms na tick, wiec sam nie robi zwiechy.
- Zgaduje przyczyne: farma mobow, zalegajace przedmioty, redstone/hoppery, skupisko graczy, ogolne przeciazenie encjami.
- Wysyla embed na Discorda z koordynatami, przyczyna i sugerowana akcja - jezykiem admina, nie profilera.
- Cooldown miedzy alertami (domyslnie 5 min), zeby nie zaspamowac kanalu.
- Po ustaniu lagu wysyla osobna, zielona wiadomosc "serwer wrocil do normy" z czasem trwania incydentu.
- Zapisuje incydenty do SQLite, wiec historia przezywa restart, a `/lagwatch stats` pokazuje,
  o ktorej godzinie i przez co serwer laguje najczesciej.
- Jesli na serwerze jest **spark**, dokleja jego statystyki (srednia i 95. percentyl czasu ticku).
- Jesli jest **PlaceholderAPI**, wystawia `%ticksentry_tps%`, `%ticksentry_mspt%` i kilka innych.

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
  recovery-alert: true       # wiadomosc po powrocie serwera do normy
  recovery-seconds: 15       # ile spokoju oznacza koniec incydentu
discord:
  enabled: true
  webhook-url: ""
  mention-role-id: ""        # opcjonalne ID roli do oznaczenia (same cyfry)
scan:
  ignored-worlds: []
  top-chunks-count: 5
storage:
  enabled: true              # false = historia tylko w pamieci, znika po restarcie
  keep-days: 30              # po ilu dniach kasowac stare wpisy (0 = trzymaj wszystko)
```

Webhook zdobywasz tak: ustawienia kanalu na Discordzie -> Integracje -> Webhooki -> Utworz webhook -> kopiuj URL.

## Komendy

Wszystkie wymagaja uprawnienia `ticksentry.admin` (domyslnie: OP).

| Komenda | Opis |
| --- | --- |
| `/lagwatch status` | biezacy TPS, czas ticku, stan monitoringu i cooldownu |
| `/lagwatch report` | wymusza skan i wypisuje wynik w czacie |
| `/lagwatch report discord` | to samo, ale wysyla tez na webhook (dobre do sprawdzenia konfiguracji) |
| `/lagwatch history` | ostatnie incydenty, takze sprzed restartu serwera |
| `/lagwatch stats [dni]` | podsumowanie: ile incydentow, przez co, o ktorej godzinie (domyslnie 7 dni) |
| `/lagwatch reload` | przeladowuje `config.yml` |

Aliasy: `/ts`, `/ticksentry`.

## Placeholdery (wymagaja PlaceholderAPI)

| Placeholder | Zwraca |
| --- | --- |
| `%ticksentry_tps%` | TPS, np. `19.9` |
| `%ticksentry_mspt%` | sredni czas ticku w ms |
| `%ticksentry_peak_ms%` | najdluzsza zwiecha w oknie pomiarowym |
| `%ticksentry_status%` | `OK` albo `LAG` |
| `%ticksentry_monitoring%` | `aktywny` albo `zatrzymany` |
| `%ticksentry_last_category%` | przyczyna ostatniego incydentu |
| `%ticksentry_incidents_24h%` | liczba incydentow z ostatniej doby |

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
├── placeholders/TickSentryExpansion  opcjonalne placeholdery PlaceholderAPI
└── storage/
    ├── AlertStore           interfejs skladu incydentow
    ├── SqliteAlertStore     zapis na dysk, cale I/O na osobnym watku
    ├── MemoryAlertStore     zapas, gdy zapis wylaczony lub baza nie dziala
    ├── StoredIncident       splaszczony incydent (jeden wiersz w bazie)
    └── IncidentStats        podsumowanie okresu: przyczyny, rozklad dobowy
```

Sterownik SQLite nie jest wbudowany w jar - deklaruje go `libraries` w `plugin.yml`,
wiec Paper pobiera go sam przy pierwszym starcie. Jar zostaje przez to lekki (~60 KB)
i nie ma konfliktow wersji z innymi pluginami.

Testy jednostkowe (`./gradlew test`) pokrywaja logike oceny chunkow i budowanie JSON-a embeda -
20 testow, bez mockowania Bukkita.

## Skad wziete progi

Wagi i progi w `HotspotAnalyzer` byly kalibrowane na dzialajacym serwerze Paper 1.21.10:

- `MIN_INTERESTING_SCORE = 80` - przy 25 zwykly chunk z kilkudziesiecioma spadajacymi blokami
  podczas generowania terenu dostawal etykiete "farma mobow".
- `FALLING_BLOCK`, `ARROW`, `SNOWBALL` maja obnizone wagi - pojawiaja sie masowo, ale krotko.
- Gracz wazy 5.0, bo trzyma zaladowane chunki wokol siebie i generuje ruch sieciowy.

Jesli na Twoim serwerze alerty sa zbyt czule albo zbyt gluche, to sa pierwsze miejsca do zmiany.
