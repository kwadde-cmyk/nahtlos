# Datenschutzerklärung

Nahtlos ist eine Podcast-App für das eigene Telefon und für Android Automotive. Es gibt kein Konto beim Entwickler und keine Analyse- oder Werbe-SDKs.

## Was die App speichert

Auf dem Gerät liegen Abos, Folgen, Wiedergabepositionen, heruntergeladene Audiodateien und die Einstellungen. Das bleibt in der App-Datenbank auf dem Gerät.

Der Sync-Server ist der Server, den du selbst einrichtest und in den Einstellungen einträgst. Die App schickt dorthin nur das, was zum Abgleich nötig ist: Feed-Adressen, Playlist und Wiedergabepositionen, dazu ein Geräte-Token. Der Server steht nicht beim Entwickler von Nahtlos. Wer ihn betreibt, sieht diese Sync-Daten.

Folgen und Cover lädt die App direkt vom Podcast-Anbieter, nicht über den Sync-Server.

Einen Podcast-Index-Schlüssel, falls du ihn einträgst, speichert die App lokal. Er geht nicht an den Sync-Server.

## Wallet und Boosts

Der NWC-Link (`nostr+walletconnect://…`) für Alby Hub oder ein anderes Wallet liegt nur auf dem Gerät, verschlüsselt mit dem Android-Keystore (EncryptedSharedPreferences). Die App sendet ihn nicht an den Sync-Server, schreibt ihn nicht ins Log und nicht in die Diagnose. Zahlungen laufen direkt zwischen dem Gerät und dem Relay, das im Link steht, und dem Wallet. Eine Play-Fassung kann Boosts abschalten (`NAHTLOS_PAYMENTS=false`); dann gibt es diesen Bereich nicht.

## Diagnose

Absturz- und Diagnose-Texte bleiben auf dem Gerät, bis du sie selbst teilst. Der Wallet-Link steht dort nicht drin.

## Weitergabe

Keine Tracker, keine Werbenetzwerke, kein Verkauf von Daten. Netzwerkverkehr gibt es zum selbst gewählten Sync-Server, zu den Podcast-Feeds, optional zum Podcast Index und, wenn Boosts an sind, zum Wallet-Relay aus dem NWC-Link.

---

# Privacy policy

Nahtlos is a podcast app for a phone and for Android Automotive. There is no developer account and no analytics or advertising SDK.

## What the app stores

Subscriptions, episodes, playback positions, downloaded audio and settings stay in the app database on the device.

The sync server is the one you host yourself and enter in settings. The app sends only what sync needs: feed addresses, the playlist and playback positions, plus a device token. That server is not run by the Nahtlos developer. Whoever operates it can see those sync records.

Episodes and artwork are downloaded from the podcast host, not through the sync server.

A Podcast Index key, if you enter one, stays on the device. It is not sent to the sync server.

## Wallet and boosts

The NWC link (`nostr+walletconnect://…`) for Alby Hub or another wallet is stored only on the device, encrypted with the Android Keystore (EncryptedSharedPreferences). The app does not send it to the sync server, and it is not written to logs or the diagnostics screen. Payments go directly between the device, the relay named in the link, and the wallet. A Play build can turn boosts off (`NAHTLOS_PAYMENTS=false`); that build has no wallet section.

## Diagnostics

Crash and diagnostic text stays on the device until you choose to share it. The wallet link is not included.

## Sharing

No trackers, no ad networks, no sale of data. Network traffic goes to the sync server you chose, to podcast feeds, optionally to Podcast Index, and, when boosts are enabled, to the wallet relay from the NWC link.
