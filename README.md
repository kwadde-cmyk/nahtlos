# Nahtlos

Self-hosted Podcast-App für das Android-Telefon und für Android Automotive OS (AAOS, das eingebaute Infotainment). Android Auto (Projektion) ist kein Ziel.

Im Mittelpunkt steht die Wiedergabeposition: auf dem Telefon pausieren, ins Auto steigen, auf dem Headunit an derselben Millisekunde weiterhören — und umgekehrt.

## Architektur

Zwei APKs, eine Codebasis, ein kleiner Sync-Dienst.

```text
Telefon (Compose) ──PUT/GET──┐
                              ├── Sync-Dienst (Ktor, SQLite, Token)
AAOS (Media3)  ──PUT/GET──────┘
        │                         optional WebSocket /v1/stream
        └──── RSS / Podcasting 2.0 (jeder Client lädt den Feed selbst)
```

| Modul | Rolle |
| --- | --- |
| `core-model` | Reines Kotlin: Feed-Parser, Episoden-Schlüssel, Last-Write-Wins, Outbox-Plan, Cache-Größe |
| `core-android` | Room, OkHttp-Sync, Outbox, WorkManager, Media3-Cache, `MediaLibraryService` |
| `mobile` | Jetpack-Compose-App: RSS abonnieren, Folgen, Player, Server-Einstellungen |
| `automotive` | AAOS-Media-App plus geparkter Einrichtungs-Screen |
| `server` | Self-hosted Sync: Abos, Positionen, WebSocket |

Jellyfin und Shark Marmalade bleiben für Musik. Dieses Projekt spricht sie nicht an.

### Positions-Sync

Pro Episode ein Dokument: `positionMs`, `durationMs`, `updatedAt` (Client-Uhr, Millisekunden), `deviceId`, `status`, optional `serverReceivedAt`.

Konfliktregel, auf dem Server und auf dem Client dieselbe:

1. Der höhere Client-Zeitstempel gewinnt. Ein späterer Rücksprung schlägt eine frühere, weiter vorne liegende Position.
2. Gleicher Zeitstempel: die höhere Position gewinnt.
3. Immer noch gleich: die höhere Server-Empfangszeit gewinnt.
4. Völliger Gleichstand: der vorhandene Datensatz bleibt (Wiederholungen sind idempotent).

Auslöser für den Upload: Pause, Stop, Seek, Ende, etwa alle 15 Sekunden während der Wiedergabe, und beim Beenden des Media-Dienstes.

Auslöser für den Download: Start des Media-Dienstes, App wieder im Vordergrund, sobald das Netz kommt. Vor dem Abspielen wartet der Dienst höchstens 1,5 Sekunden auf den Server und fällt sonst auf die lokale Position zurück.

Offline: die Position landet in einer Room-Outbox. Liegt das Netz wieder an und der Prozess lebt, wird sie hochgeladen. Zusätzlich plant WorkManager einen eindeutigen Job mit Netz-Constraint (`nahtlos-outbox-flush`). Der Job überlebt das Beenden der App und schickt die Position, sobald das Netz da ist — auch wenn niemand die App wieder öffnet. Eine ältere lokale Position überschreibt nie einen neueren Server-Stand; lehnt der Server ab, übernimmt der Client das gespeicherte Dokument.

### Wiedergabe-Cache

Es gibt einen Wiedergabe-Cache und lokale Playlist-Downloads. Media3 liest über `DefaultDataSource` (file, content und http) und dahinter `CacheDataSource` plus `SimpleCache` (LRU im App-Cache). Eine heruntergeladene Folge startet als `file://`; schlägt die Datei fehl, springt der Player einmal auf die Stream-URL. Der Load-Controller liest voraus, bis das eingestellte Fenster voll ist, standardmäßig 10 Minuten, einstellbar von 2 bis 30. Die Wiedergabe startet nach etwa einer Sekunde, nicht erst nach dem ganzen Fenster. Ein Rücksprung von 15 oder 30 Sekunden bleibt aus dem Cache bedient, solange die Stelle schon geladen wurde.

Die Größe rechnet `PlaybackCachePolicy` mit 320 kbit/s: der RAM-Deckel liegt etwas über diesem Fenster (höchstens 64 MB), der Disk-Cache fasst drei solcher Fenster (mindestens 64 MB, höchstens 512 MB). Leisere Feeds passen zeitlich vollständig hinein. Deutlich höhere Bitraten können den Byte-Deckel vor der vollen Minutenzahl erreichen.

Auf AAOS bereitet der Media-Dienst beim Start die Folge aus „Weitermachen“ vor, ohne sie abzuspielen, sofern Netz da ist. Damit füllt sich der Cache ab der gespeicherten Position, bevor man in ein Funkloch fährt. Die Positions-Outbox läuft davon unabhängig weiter.

Episoden-Identität ist `podcast:guid` plus GUID der Folge. Die Enclosure-URL ist nur der Fallback, weil CDN-Links sich ändern.

Beide Geräte brauchen eine brauchbare Uhr. Die Client-Zeit ist die Autorität. Liegt die Uhr im Auto um Jahre daneben, gewinnt dauerhaft das Gerät mit der „Zukunft“.

### Podcasting 2.0 im MVP

Der Parser liest aus dem RSS-Feed, als Stream (Woodstox/StAX, kein DOM über das ganze Dokument):

- `podcast:guid` am Channel
- GUID und Enclosure der Folge
- `podcast:person` (Name, Rolle, Link, Bild)
- `podcast:transcript` (URL, Typ, Sprache) — im Player als Link, kein eingebauter Reader
- `podcast:chapters` als URL plus Podlove Simple Chapters (`psc:chapter`) inline
- die Kapitel-JSON-Datei (`application/json+chapters`) wird beim Öffnen des Players nachgeladen

Ein OPML-Import schreibt sofort nur Titel und Feed-URL, lädt die OPML wie bisher auf den Server und antwortet mit „36 Feeds übernommen, lade Folgen…“. Die Folgen holt WorkManager danach im Hintergrund (`nahtlos-feed-refresh`), auch wenn man den Screen verlässt: höchstens vier Feeds gleichzeitig, 45 Sekunden pro Feed, ein fehlerhafter Feed bricht die anderen nicht ab. Die Bibliothek zeigt `x/36`. Fehlgeschlagene Feeds sind markiert und lassen sich einzeln oder gesammelt erneut laden.

Der Parser ist ein Pull-Parser (`XmlPullParser`, auf dem Handy die Android-Implementierung, in den JVM-Tests kxml2). StAX/`javax.xml.stream` wird nicht verwendet. Beim Aktualisieren gilt die Einstellung „Folgen nachladen für die letzten …“ (Standard 4 Wochen; 1 oder 2 Wochen, 3 oder 6 Monate, 1 Jahr, oder alle). Ist der Feed nach Datum absteigend sortiert, bricht das Lesen ab, sobald die Folgen älter als das Fenster sind. Folgen, die schon gespeichert sind — insbesondere mit Position oder in der Playlist — werden nicht gelöscht. Die Playlist „seit Datum“ behält Einträge, deren Folge lokal noch nicht da ist, und löst sie nach dem Feed-Abruf über die GUID oder die Enclosure-URL auf. Ein einzelner kaputter Feed markiert nur sich selbst als Fehler und reißt den Rest nicht mit.

Ein leeres Playlist-Dokument vom Server ersetzt keine lokale Liste, auch wenn sein Zeitstempel neuer ist. Der Client schreibt die lokalen Folgen mit einem späteren Stempel zurück, damit ein alter leerer Stand auf dem Server die Liste nicht löscht. Fehlt `/v1/playlist` (404) oder ist die Antwort unbekannt, bleibt die lokale Liste. Wischen schaltet um: gehört wird ungehört, „Zur Playlist“ nimmt eine Folge wieder heraus, „Download löschen“ lädt sie, wenn sie noch nicht da ist. Sync-Aufrufe brechen nach 12 Sekunden ab. Die Wiedergabe startet mit der lokalen Position; eine neuere Position vom anderen Gerät wird danach gesucht, nicht vorher. Die Media-Session nimmt das eigene Paket, den Benachrichtigungs-Controller und die System-Controller (SystemUI, Android Automotive) immer an. Fehler landen unter Einstellungen → Diagnose.

Cover lädt Coil mit Speicher-Cache und 250 MB Disk-Cache. Fürs Auto legt die App die Bilder lokal ab und gibt sie über `content://` an den MediaBrowser, damit das Headunit sie nicht neu lädt.

Wiedergabe: Tempo 0,5× bis 3,0× in 0,1-Schritten (global und pro Podcast), Stille überspringen, Lautstärke-Anhebung, Sprung vor/zurück, Intro- und Outro-Schnitt pro Podcast. Der Schlaf-Timer gibt es nur am Telefon. Im Auto sind Tempo, Sprung und Kapitel eigene MediaSession-Aktionen; der Einrichtungsbildschirm bleibt ohne `distractionOptimized`.

Podcasting 2.0: Kapitel (JSON, im Player mit Bild und Link, im Auto vor/zurück, aktueller Kapiteltitel als Untertitel nach dem Sprung), Transkript mit Markierung und Sprung, Personen, Funding, Soundbite, Staffel/Folge, locked, alternateEnclosure, liveItem und Value-Empfänger (nur Anzeige, keine Zahlung). Shownotes mit HTML werden im Player gerendert. Tempo und Schalter pro Podcast bleiben auf dem Gerät; sie werden nicht mit dem Server synchronisiert.

Ein unbehandelter Absturz schreibt den Stacktrace nach `filesDir/last-crash.txt`. Sync- und Player-Fehler schreibt `filesDir/diagnose.txt`. Beides steht unter Einstellungen → Diagnose und lässt sich teilen.

Am Telefon sind die Aktionen beschriftet. Oben bleiben Playlist, Downloads und Einstellungen Icons; langes Drücken zeigt den Namen. OPML-Import und -Export stehen im Menü „⋮“ als Text. „Podcast hinzufügen“ ist ein Button mit Icon und Text und öffnet die RSS-Adresse. Die Playlist sortiert man am Griff links, einmal am Ende des Ziehens, und die neue Reihenfolge geht mit auf den Server. Entfernen läuft über Wischen oder die Mehrfachauswahl, nicht über ein Papierkorb-Icon. Wischen und langes Drücken erklärt ein Hinweis beim ersten Start; leere Listen sagen, wie man Folgen hineinbekommt.

### AAOS

`automotive` verlangt `android.hardware.type.automotive` und liefert `res/xml/automotive_app_desc.xml` mit `<uses name="media"/>`. Die System-UI zeichnet den Browse-Baum (Weitermachen, Abonnements, Folgen). Der Media-Dienst hängt an `androidx.media3.session.MediaLibraryService` und zusätzlich am Legacy-Action `android.media.browse.MediaBrowserService`, damit das Headunit die App findet. Pakete: `de.jenz.nahtlos` und `de.jenz.nahtlos.automotive`.

Ohne Server-URL und Token liefert die Automotive-App einen leeren Baum mit der Aktion „Server einrichten“. Der Einrichtungs-Screen ist eine normale Activity ohne `distractionOptimized` und damit nur im Parkzustand benutzbar. Im Auto tippt man kein Token mehr: „Mit Handy verbinden“ zeigt einen QR-Code und einen kurzen Code. Das Handy scannt ihn oder tippt den Code ab und gibt das Auto frei. Die Server-Adresse steckt im QR-Code (`nahtlos://pair?server=...&code=...`). Ist im Auto noch keine Adresse bekannt, gilt der Build-Wert `NAHTLOS_DEFAULT_SERVER` (Gradle-Property oder Umgebungsvariable, sonst leer); notfalls wird nur die Adresse eingetippt.

## Sync-Server

Token-Auth, ein Haushalt, ein Token. SQLite-Datei, kein extra Datenbank-Server.

| Methode | Pfad | Bedeutung |
| --- | --- | --- |
| GET | `/health` | ohne Token, `{"status":"ok"}` |
| GET | `/v1/subscriptions` | Aboliste |
| PUT | `/v1/subscriptions` | Feed anlegen oder aktualisieren |
| DELETE | `/v1/subscriptions?feedUrl=` | Abo löschen |
| GET | `/v1/progress` | alle Positionen, optional `?since=` (Server-Empfangszeit) |
| GET | `/v1/progress?episodeKey=` | eine Position, 404 wenn unbekannt |
| PUT | `/v1/progress` | eine Position, Antwort `{accepted, stored}` |
| PUT | `/v1/progress/batch` | bis zu 200 Positionen |
| WS | `/v1/stream?token=` | erst `{"type":"ready"}`, danach `{"type":"progress",...}` |

`Authorization: Bearer <token>` gilt für die geschützten `/v1`-Routen. Der Query-Parameter `token` ist für den WebSocket gedacht und wird zusätzlich akzeptiert. `SYNC_TOKEN` bleibt das Admin-Token. Jedes Gerät bekommt danach einen eigenen Token (SHA-256 in der Tabelle `devices`). Das Handy tauscht das Admin-Token beim ersten Speichern automatisch dagegen; das Admin-Token selbst bleibt am Server gültig.

### Geräte koppeln

Wie bei einem Smart-TV: das Auto zeigt den Code, das Handy gibt ihn frei.

| Methode | Pfad | Auth | Bedeutung |
| --- | --- | --- | --- |
| POST | `/v1/pair/start` | keine | `{pairingId, code, expiresIn}`, Code 5 Minuten, z. B. `K7P-4QX` |
| POST | `/v1/pair/approve` | Handy- oder Admin-Token | `{code, name}` gibt das Gerät frei |
| GET | `/v1/pair/poll?pairingId=` | keine | `pending`, danach einmalig `approved` mit Geräte-Token |
| POST | `/v1/devices/bootstrap` | nur Admin-Token | legt das erste Gerät an und liefert dessen Token |
| GET | `/v1/devices` | Gerät oder Admin | Liste, ohne Token |
| DELETE | `/v1/devices/{id}` | Gerät oder Admin | sperrt das Gerät |

Codes lassen `0`, `O`, `1`, `I` und `L` weg, gelten einmal und laufen ab. Zu viele Starts oder falsche Codes antworten mit 429. Ein gesperrter Geräte-Token bekommt 401.

Auf dem Handy: Einstellungen, „Auto koppeln“. Scanner ist CameraX plus ZXing, ohne Play-Dienste. Daneben die Geräteliste mit Sperren. Im Auto bleibt die Token-Eingabe als Fallback.

Die vorbelegte Auto-Adresse:

```bash
./gradlew :automotive:assembleDebug -PNAHTLOS_DEFAULT_SERVER=https://podcast.example.com
```

`accepted: false` heißt: ein anderer Schreibvorgang hat gewonnen, `stored` ist der gültige Stand. Der Client verwirft damit seine ältere Outbox-Zeile.

`/api/2/…` antwortet mit 501. Das ist nur die Tür für eine spätere gPodder-Abbildung (`play.position` in Sekunden = `positionMs / 1000`, Schlüssel über GUID statt Enclosure-URL).

### Server starten

Docker:

```bash
export SYNC_TOKEN=ein-langes-geheimnis
docker compose up --build
```

Der Dienst heißt im Compose-File `nahtlos`, das Image `nahtlos:local`. **Im Container lauscht er fest auf Port 8787.** Der Port auf dem Host ist `NAHTLOS_PORT` (Standard ebenfalls 8787):

```bash
NAHTLOS_PORT=8790 docker compose up --build
```

Die Datenbank liegt im Volume unter `/data/sync.db`.

### Nginx Proxy Manager

Lege einen Proxy Host auf die öffentliche HTTPS-Adresse an, zum Beispiel `https://podcast.example.com`.

- Scheme: `http`
- Forward Hostname: die IP oder der Docker-Name `nahtlos`
- Forward Port: `8787`, wenn NPM im selben Compose-Netz hängt, sonst der Wert von `NAHTLOS_PORT` auf dem Host
- Websockets Support: an
- SSL-Zertifikat am Proxy, nicht im Nahtlos-Container

Der Server liest `X-Forwarded-Proto`, `X-Forwarded-Host` und `X-Forwarded-For`. Die App trägt als Server-URL genau die öffentliche Adresse ein (`https://podcast.example.com`, ohne `:8787`, wenn NPM auf 443 liegt). Aus `https` macht der Client `wss` für `/v1/stream`. NPM beendet TLS und reicht die Verbindung als normales WebSocket an Port 8787 weiter.

Ohne Docker, mit JDK 17 oder 21:

```bash
export SYNC_TOKEN=ein-langes-geheimnis
export SYNC_DB=data/sync.db
export PORT=8787
./gradlew :server:installDist
./server/build/install/server/bin/server
```

Ohne gesetztes `SYNC_TOKEN` gilt `dev-token-change-me`. Das ist nur für den Schreibtisch.

Beispiel:

```bash
curl -s http://127.0.0.1:8787/health

curl -s -X PUT http://127.0.0.1:8787/v1/progress \
  -H "Authorization: Bearer $SYNC_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"episodeKey":"pod|ep","positionMs":305000,"durationMs":1800000,"updatedAt":1700000000000,"deviceId":"phone","status":"PAUSED"}'
```

Vom Android-Emulator aus ist der Rechner `http://10.0.2.2:8787`. Ein echtes Telefon im selben LAN nimmt die IP des Hosts. Cleartext-HTTP ist in den Apps erlaubt, damit ein Server im LAN ohne Zertifikat funktioniert. Unterwegs besser HTTPS vor den Dienst setzen.

## Android bauen und sideloaden

JDK 17+ und ein Android-SDK mit Platform 35. `local.properties` zeigt auf das SDK:

```properties
sdk.dir=/pfad/zum/android-sdk
```

Oder `ANDROID_HOME` setzen. Dann:

```bash
./gradlew assembleDebug
./gradlew :core-model:test :server:test
```

Die Debug-APKs:

- Telefon: `mobile/build/outputs/apk/debug/mobile-debug.apk`
- AAOS: `automotive/build/outputs/apk/debug/automotive-debug.apk`

Die Automotive-APK lässt sich nicht auf einem normalen Telefon installieren, die Telefon-APK ist keine AAOS-Media-App. Beide sind mit dem Debug-Keystore signiert und brauchen keinen Play Store.

Telefon:

```bash
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

In der App: Einstellungen öffnen, Server-URL und Token eintragen, „Verbindung prüfen“, dann über das Plus einen RSS-Feed abonnieren. Eine Folge abspielen und pausieren. Die Position geht an den Server, auch wenn das Netz kurz weg ist — dann steht sie in der Warteschlange.

AAOS-Emulator (Android Studio Device Manager, Automotive, z. B. 1024p landscape, API 32 oder neuer):

```bash
adb install -r automotive/build/outputs/apk/debug/automotive-debug.apk
```

Im geparkten Zustand „Nahtlos Auto“ öffnen und dieselbe URL, denselben Token und bei Bedarf den Puffer eintragen. Im Emulator ist der Host `http://10.0.2.2:8787`. Danach erscheint die App im Media-Center mit Weitermachen, Abonnements und Folgen. Ist Netz da, wird die aktuelle Folge schon beim Start der Media-Session in den Cache geladen. Play zieht vorher die letzte Position.

Ein echtes Fahrzeug hängt vom OEM ab (Sideload bei Polestar, Volvo, manchen GM-Modellen, sonst oft nur der Play-Automotive-Track). Das ist hier nicht ausprobiert.

## Tests

`core-model` prüft Last-Write-Wins, die Playlist (Datum, Sortierung, Refresh, Reihenfolge, Autoplay-Nachbar, Entfernen fertiger Folgen), die Download-Frist und das Speicherlimit, OPML (inkl. Hopp- und Pocket-Casts-Export), die Outbox-Planung, die Cache-Größe, den RSS-Parser und die gPodder-Sekundenabbildung. Der Import-Benchmark parst den aufgezeichneten Einundzwanzig-Feed (gzip unter `core-model/src/test/resources/feeds/`) mit und ohne 200er-Deckel und druckt Laufzeit, allokierte und behaltene Bytes. `server` prüft Token, Positionen, Playlist-Sync, OPML-Import und den WebSocket.

`ImportOnDeviceTest` in `core-android` macht dasselbe auf einem Android-Gerät: aufgezeichneten Feed parsen, beide OPML-Dateien nur als Titel speichern, ohne einen Request abzusetzen. Start mit einem laufenden Emulator oder Handy:

```bash
./gradlew :core-android:connectedDebugAndroidTest
```

`docker compose` ist in dieser Umgebung nur die Bauanleitung. `./gradlew assembleDebug` baut beide APKs.

## Was diese Version kann

- RSS per URL abonnieren, Folgen listen, streamen, OPML importieren (sofort die Abos, Folgen im Hintergrund) und exportieren
- Playlist „alles seit Datum“ mit Sortierung, Ziehen am Griff, Entfernen per Wischen, Sync aufs Auto und Autoplay der nächsten Folge
- Automatischer Download der Playlist je Gerät (WLAN-Wunsch und Speicherlimit), lokale Datei vor Stream
- Fertige Downloads nach einer Wartezeit löschen (Standard 24 Stunden) und optional aus der Playlist nehmen
- Wiedergabe-Cache für etwa 10 Minuten Funkloch, einstellbar, mit Vorladen der aktuellen Folge im Auto
- Geräte-Token statt eines gemeinsamen Tokens im Auto: QR-Code oder Kurzcode, Freigabe vom Handy, Sperren in der Geräteliste
- Pause, Seek, ±15/30 Sekunden, Kapitel wenn sie im Feed oder in der Kapiteldatei stehen
- Position lokal speichern und mit dem Server abgleichen, in beide Richtungen
- Outbox, WorkManager-Flush, Pull beim Session-Start und im Vordergrund, 1,5-Sekunden-Grenze vor dem Play
- WebSocket, solange die Verbindung steht; Pull bleibt die Quelle der Wahrheit
- Browse-Baum und geparkte Einrichtung auf AAOS

## Was noch nicht da ist

- Downloads außerhalb der Playlist (nur die Playlist wird automatisch geladen; der Cache bleibt LRU)
- Up Next / Queue
- Podcast-Index-Suche
- Transkript im Player, nur der Link
- gPodder-kompatible API (bewusst 501)
- Mehrere Benutzer, Kontoanlage, QR-Kopplung — ein gemeinsames Token
- Gleichzeitiges Abspielen auf beiden Geräten: Last-Write-Wins, sie „kämpfen“
- Play-Automotive-Track und OEM-Sideload-Tests
- Messung des Caches in einem echten Funkloch

## Nächste Schritte

1. Phone-Emulator und AAOS-Emulator gegeneinander: Pause links, Play rechts, unter zwei Sekunden bei stehendem Netz. Danach Netz trennen und prüfen, ob etwa 10 Minuten weiterlaufen.
2. Kapitel im AAOS-Browse (Seek über die System-UI ist da, eine Kapitelliste im Auto noch nicht).
3. gPodder-Adapter nur wenn ein bestehendes oPodSync weiterlaufen soll — nicht als Ersatz für diesen Vertrag.
