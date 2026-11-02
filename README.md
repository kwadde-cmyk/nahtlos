# Weiterhören

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
| `core-model` | Reines Kotlin: Feed-Parser, Episoden-Schlüssel, Last-Write-Wins, Outbox-Plan |
| `core-android` | Room, OkHttp-Sync, Outbox, `MediaLibraryService` (Media3) |
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

Offline: die Position landet in einer Room-Outbox. Liegt das Netz wieder an und der Prozess lebt, wird sie hochgeladen. War der Prozess tot, passiert das beim nächsten Start von App oder Media-Session. Eine ältere lokale Position überschreibt nie einen neueren Server-Stand; lehnt der Server ab, übernimmt der Client das gespeicherte Dokument.

Episoden-Identität ist `podcast:guid` plus GUID der Folge. Die Enclosure-URL ist nur der Fallback, weil CDN-Links sich ändern.

Beide Geräte brauchen eine brauchbare Uhr. Die Client-Zeit ist die Autorität. Liegt die Uhr im Auto um Jahre daneben, gewinnt dauerhaft das Gerät mit der „Zukunft“.

### Podcasting 2.0 im MVP

Der Parser liest aus dem RSS-Feed:

- `podcast:guid` am Channel
- GUID und Enclosure der Folge
- `podcast:person` (Name, Rolle, Link, Bild)
- `podcast:transcript` (URL, Typ, Sprache) — im Player als Link, kein eingebauter Reader
- `podcast:chapters` als URL plus Podlove Simple Chapters (`psc:chapter`) inline
- die Kapitel-JSON-Datei (`application/json+chapters`) wird beim Öffnen des Players nachgeladen

### AAOS

`automotive` verlangt `android.hardware.type.automotive` und liefert `res/xml/automotive_app_desc.xml` mit `<uses name="media"/>`. Die System-UI zeichnet den Browse-Baum (Weiterhören, Abonnements, Folgen). Der Media-Dienst hängt an `androidx.media3.session.MediaLibraryService` und zusätzlich am Legacy-Action `android.media.browse.MediaBrowserService`, damit das Headunit die App findet.

Ohne Server-URL und Token liefert die Automotive-App einen leeren Baum mit der Aktion „Server einrichten“. Der Einrichtungs-Screen ist eine normale Activity ohne `distractionOptimized` und damit nur im Parkzustand benutzbar.

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

`Authorization: Bearer <token>` gilt für alle `/v1`-Routen. Der Query-Parameter `token` ist für den WebSocket gedacht und wird zusätzlich akzeptiert.

`accepted: false` heißt: ein anderer Schreibvorgang hat gewonnen, `stored` ist der gültige Stand. Der Client verwirft damit seine ältere Outbox-Zeile.

`/api/2/…` antwortet mit 501. Das ist nur die Tür für eine spätere gPodder-Abbildung (`play.position` in Sekunden = `positionMs / 1000`, Schlüssel über GUID statt Enclosure-URL).

### Server starten

Docker:

```bash
export SYNC_TOKEN=ein-langes-geheimnis
docker compose up --build
```

Der Dienst lauscht auf Port 8787, die Datenbank liegt im Volume unter `/data/sync.db`.

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

Im geparkten Zustand „Weiterhören Auto“ öffnen und dieselbe URL und denselben Token eintragen. Im Emulator ist der Host `http://10.0.2.2:8787`. Danach erscheint die App im Media-Center mit Weiterhören, Abonnements und Folgen. Play zieht vorher die letzte Position.

Ein echtes Fahrzeug hängt vom OEM ab (Sideload bei Polestar, Volvo, manchen GM-Modellen, sonst oft nur der Play-Automotive-Track). Das ist hier nicht ausprobiert.

## Tests

`core-model` prüft Last-Write-Wins, die Outbox-Planung, den RSS-Parser (GUID, Personen, Transkript, Kapitel-URL, Podlove-Kapitel) und die gPodder-Sekundenabbildung. `server` prüft Token, Roundtrip, älteren Schreibvorgang, späteren Rücksprung, Batch, Abos und den WebSocket.

Nicht gelaufen: Android-Emulator, AAOS-Emulator, instrumentierte Tests, `docker compose` in dieser Umgebung nur als Bauanleitung. `./gradlew assembleDebug` baut beide APKs.

## Was diese Version kann

- RSS per URL abonnieren, Folgen listen, streamen (kein Offline-Audio)
- Pause, Seek, ±15/30 Sekunden, Kapitel wenn sie im Feed oder in der Kapiteldatei stehen
- Position lokal speichern und mit dem Server abgleichen, in beide Richtungen
- Outbox, Pull beim Session-Start und im Vordergrund, 1,5-Sekunden-Grenze vor dem Play
- WebSocket, solange die Verbindung steht; Pull bleibt die Quelle der Wahrheit
- Browse-Baum und geparkte Einrichtung auf AAOS

## Was noch nicht da ist

- Downloads und eine echte Offline-Wiedergabe
- Up Next / Queue
- Podcast-Index-Suche
- Transkript im Player, nur der Link
- gPodder-kompatible API (bewusst 501)
- Mehrere Benutzer, Kontoanlage, QR-Kopplung — ein gemeinsames Token
- WorkManager für einen Flush, wenn der Prozess schon tot ist und niemand die App öffnet
- Gleichzeitiges Abspielen auf beiden Geräten: Last-Write-Wins, sie „kämpfen“
- Play-Automotive-Track und OEM-Sideload-Tests

## Nächste Schritte

1. Phone-Emulator und AAOS-Emulator gegeneinander: Pause links, Play rechts, unter zwei Sekunden bei stehendem Netz.
2. WorkManager mit Netz-Constraint, damit die Outbox auch ohne geöffnete UI rausgeht.
3. Offline-Downloads der Enclosure, damit der Tunnel nicht die Wiedergabe abreißt.
4. Kapitel im AAOS-Browse (Seek über die System-UI ist da, eine Kapitelliste im Auto noch nicht).
5. gPodder-Adapter nur wenn ein bestehendes oPodSync weiterlaufen soll — nicht als Ersatz für diesen Vertrag.
