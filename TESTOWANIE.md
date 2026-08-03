# Jak przetestowac TickSentry

Instrukcja krok po kroku: od pustego serwera do sprawdzenia wszystkich funkcji.
Caly test zajmuje okolo 15 minut.

## Zanim zaczniesz

Potrzebujesz serwera Paper 1.20.6 lub nowszego i Javy 21.

1. Zbuduj plugin: `./gradlew build`
2. Skopiuj `build/libs/TickSentry-1.0.0.jar` do `plugins/` na serwerze
3. Uruchom serwer raz i wylacz go - powstanie `plugins/TickSentry/config.yml`

## Ustawienia na czas testu

Domyslnie alert leci po 10 sekundach przeciazenia, a kolejny dopiero po 5 minutach.
Przy testowaniu to za dlugo. Podmien w `plugins/TickSentry/config.yml`:

```yaml
monitor:
  mspt-threshold-ms: 50
  sustained-seconds: 3       # zamiast 10
  scan-cooldown-seconds: 15  # zamiast 300
  recovery-seconds: 10       # zamiast 15
dashboard:
  enabled: true              # panel webowy do testu
```

**Po skonczonym tescie przywroc wartosci domyslne** - inaczej przy pierwszym
prawdziwym lagu dostaniesz alert co 15 sekund.

## Krok 1: czy plugin w ogole wstal

Uruchom serwer i poszukaj w konsoli:

```
[TickSentry] TickSentry aktywny - prog 50.0 ms przez 3 s. Historia: SQLite (incidents.db).
[TickSentry] Panel webowy: http://127.0.0.1:8080/?token=...
```

Jesli zamiast `SQLite` widzisz `pamiec`, sterownik bazy sie nie pobral - sprawdz,
czy serwer ma dostep do internetu przy pierwszym starcie.

Linia `Wykryto spark` pojawi sie tylko, gdy masz sparka (Paper ma go wbudowanego).

## Krok 2: podstawowy odczyt

W grze albo w konsoli serwera:

```
/lagwatch status
```

Powinienes zobaczyc TPS okolo 20, czas ticku w granicach 1-20 ms i `Monitoring: aktywny`.

## Krok 3: zrob prawdziwy lag

Wejdz do gry, odejdz kawalek od spawnu i stan w miejscu. Najpierw jedna krowa:

```
/summon minecraft:cow ~ ~ ~
```

Potem komenda, ktora **podwaja** liczbe krow. Kazde uzycie to dwa razy wiecej encji:

```
/execute as @e[type=cow,distance=..10] at @s run summon minecraft:cow ~ ~ ~
```

Powtorz ja okolo 11 razy: 2, 4, 8, 16 ... 2048 krow. Od mniej wiecej 1000 sztuk
serwer zaczyna wyraznie zwalniac - o to chodzi.

> Jesli testujesz z konsoli serwera zamiast z gry, dodaj wspolrzedne:
> `/execute positioned 100 70 100 run summon minecraft:cow 100 70 100`,
> a chunk najpierw przytrzymaj przez `/forceload add 100 100`.

## Krok 4: co powinno sie stac

Po okolo 3 sekundach przeciazenia w konsoli:

```
[TickSentry] Wykryto trwaly lag: MSPT 80.1 ms, TPS 18.29. Przyczyna: Farma mobow.
[TickSentry]  - world @ 1608, 1608 (encje: 1292, block-entity: 0)
[TickSentry] Sugestia: Skocz na miejsce (/tp 1608 ~ 1608). Podejrzana farma: 841x cow.
              Doraznie: /kill @e[type=cow,x=1600,y=-64,z=1600,dx=16,dy=384,dz=16]
```

Sprawdz, czy podane wspolrzedne to faktycznie miejsce, w ktorym stoisz. To jest
najwazniejszy test calego pluginu - czy trafia we wlasciwy chunk.

Rownolegle:

- `/lagwatch status` pokazuje czas ticku powyzej progu
- `/lagwatch report` wypisuje piec najbardziej podejrzanych chunkow
- panel webowy zmienia plakietke na czerwona `trwa lag`, a wykres przebija linie progu

## Krok 5: sprzatanie i powrot do normy

```
/kill @e[type=minecraft:cow]
/kill @e[type=minecraft:item]
```

Druga komenda jest wazna: po zabiciu krow zostaje kilka tysiecy dropow, ktore same
w sobie potrafia lagowac. Przy okazji zobaczysz, ze plugin **zmienia rozpoznana
przyczyne** na "Zalegajace przedmioty" - to dobry test kategoryzacji.

Po okolo 10 sekundach spokoju:

```
[TickSentry] Serwer wrocil do normy po 21 s.
```

## Krok 6: historia i statystyki

```
/lagwatch history
/lagwatch stats 7
```

`history` pokazuje incydenty z czasem i miejscem, `stats` podsumowanie z rozkladem
dobowym. **Zrestartuj serwer i wpisz `/lagwatch history` jeszcze raz** - wpisy musza
tam nadal byc. To dowod, ze zapis do SQLite dziala.

## Krok 7: panel webowy

Wklej do przegladarki adres z konsoli (razem z tokenem):

```
http://127.0.0.1:8080/?token=twoj-token
```

Sprawdz przy okazji, czy kontrola dostepu dziala - ten adres bez `?token=...`
powinien zwrocic `401`.

Panel odswieza sie sam co 2 sekundy. Jesli wykres jest pusty, poczekaj chwile -
probki zbierane sa co 5 sekund.

## Krok 8: alert na Discordzie

1. Discord: kanal -> **Edytuj kanal** -> **Integracje** -> **Webhooki** -> **Nowy webhook**
2. **Kopiuj URL webhooka**
3. W `config.yml`:
   ```yaml
   discord:
     enabled: true
     webhook-url: "https://discord.com/api/webhooks/..."
   ```
4. `/lagwatch reload`
5. `/lagwatch report discord` - embed powinien pojawic sie na kanale w sekunde

Jesli nic nie przyszlo, sprawdz konsole - plugin wypisuje tam kod odpowiedzi Discorda.

Adres webhooka jest jak haslo: kto go ma, moze pisac na Twoj kanal. Nie wrzucaj go
do repozytorium ani na screeny.

## Krok 9: przywroc ustawienia

Wroc do `sustained-seconds: 10`, `scan-cooldown-seconds: 300`, `recovery-seconds: 15`
i zrob `/lagwatch reload`.

## Czego test nie sprawdzi

Plugin szuka lagu **w zawartosci swiata**. Nie wykryje przeciazenia pochodzacego
z innego pluginu, zapisu mapy czy generowania terenu - w takiej sytuacji zaraportuje
kategorie "Nieoczywiste zrodlo" i zasugeruje odpalenie sparka. To swiadome ograniczenie,
nie blad.
