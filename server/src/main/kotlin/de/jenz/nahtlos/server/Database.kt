package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.NostrIdentityBody
import de.jenz.nahtlos.model.NostrSignStatus
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.model.ProgressWriteResponse
import de.jenz.nahtlos.model.Subscription
import de.jenz.nahtlos.playlist.ListeningPlaylist
import de.jenz.nahtlos.playlist.PlaylistResolver
import de.jenz.nahtlos.playlist.PlaylistWriteResponse
import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.diagnose.DiagnoseNote
import de.jenz.nahtlos.sync.BoostRecipientSync
import de.jenz.nahtlos.sync.BoostSync
import de.jenz.nahtlos.sync.BoostSyncItem
import de.jenz.nahtlos.sync.ProgressResolver
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

class SyncDatabase(path: String) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        autoCommit = true
    }
    private val lock = Any()

    init {
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS subscriptions (
                      feed_url TEXT PRIMARY KEY,
                      title TEXT NOT NULL,
                      podcast_guid TEXT,
                      image_url TEXT,
                      updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS playlist (
                      id TEXT PRIMARY KEY,
                      document TEXT NOT NULL,
                      updated_at INTEGER NOT NULL,
                      server_received_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS devices (
                      id TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      token_hash TEXT NOT NULL UNIQUE,
                      created_at INTEGER NOT NULL,
                      last_seen_at INTEGER,
                      revoked INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pairings (
                      pairing_id TEXT PRIMARY KEY,
                      code TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL,
                      device_name TEXT,
                      device_id TEXT,
                      device_token TEXT,
                      consumed INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS wallet_relay (
                      session_id TEXT PRIMARY KEY,
                      recipient_device_id TEXT NOT NULL,
                      sender_device_id TEXT,
                      ciphertext TEXT,
                      created_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS diagnose (
                      device_id TEXT PRIMARY KEY,
                      updated_at INTEGER NOT NULL,
                      body TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS boosts (
                      id TEXT PRIMARY KEY,
                      at_ms INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      total_sats INTEGER NOT NULL,
                      podcast TEXT NOT NULL,
                      episode TEXT NOT NULL,
                      recipients_json TEXT NOT NULL,
                      device_id TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS nostr_identity (
                      id INTEGER PRIMARY KEY CHECK (id = 1),
                      pubkey TEXT NOT NULL,
                      name TEXT NOT NULL,
                      picture TEXT,
                      updated_at INTEGER NOT NULL,
                      device_id TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS nostr_sign (
                      id TEXT PRIMARY KEY,
                      kind INTEGER NOT NULL,
                      content TEXT NOT NULL,
                      tags_json TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      status TEXT NOT NULL,
                      event_json TEXT,
                      error TEXT,
                      requester_device_id TEXT NOT NULL,
                      opened_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS progress (
                      episode_key TEXT PRIMARY KEY,
                      podcast_guid TEXT,
                      episode_guid TEXT,
                      feed_url TEXT,
                      position_ms INTEGER NOT NULL,
                      duration_ms INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      device_id TEXT NOT NULL,
                      status TEXT NOT NULL,
                      server_received_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    fun listSubscriptions(): List<Subscription> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT feed_url, title, podcast_guid, image_url, updated_at FROM subscriptions ORDER BY title COLLATE NOCASE",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                val list = mutableListOf<Subscription>()
                while (rows.next()) {
                    list += Subscription(
                        feedUrl = rows.getString(1),
                        title = rows.getString(2),
                        podcastGuid = rows.getString(3),
                        imageUrl = rows.getString(4),
                        updatedAt = rows.getLong(5),
                    )
                }
                list
            }
        }
    }

    fun upsertSubscription(subscription: Subscription, now: Long) = synchronized(lock) {
        connection.prepareStatement(
            """
            INSERT INTO subscriptions(feed_url, title, podcast_guid, image_url, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(feed_url) DO UPDATE SET
              title = excluded.title,
              podcast_guid = excluded.podcast_guid,
              image_url = excluded.image_url,
              updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, subscription.feedUrl)
            statement.setString(2, subscription.title)
            statement.setString(3, subscription.podcastGuid)
            statement.setString(4, subscription.imageUrl)
            statement.setLong(5, now)
            statement.executeUpdate()
        }
    }

    fun deleteSubscription(feedUrl: String): Boolean = synchronized(lock) {
        connection.prepareStatement("DELETE FROM subscriptions WHERE feed_url = ?").use { statement ->
            statement.setString(1, feedUrl)
            statement.executeUpdate() > 0
        }
    }

    fun listProgress(since: Long?): List<EpisodeProgress> = synchronized(lock) {
        val sql = if (since == null) {
            "SELECT * FROM progress ORDER BY updated_at DESC"
        } else {
            "SELECT * FROM progress WHERE server_received_at > ? ORDER BY updated_at DESC"
        }
        connection.prepareStatement(sql).use { statement ->
            if (since != null) statement.setLong(1, since)
            statement.executeQuery().use { rows ->
                val list = mutableListOf<EpisodeProgress>()
                while (rows.next()) list += rows.toProgress()
                list
            }
        }
    }

    fun findProgress(episodeKey: String): EpisodeProgress? = synchronized(lock) {
        connection.prepareStatement("SELECT * FROM progress WHERE episode_key = ?").use { statement ->
            statement.setString(1, episodeKey)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toProgress() else null
            }
        }
    }

    /**
     * Applies last-write-wins. [ProgressWriteResponse.accepted] is false when the
     * stored document won; the caller should adopt [ProgressWriteResponse.stored].
     * Returns whether client-visible fields changed, so identical retries do not fan out.
     */
    fun applyProgress(incoming: EpisodeProgress, now: Long): ApplyResult = synchronized(lock) {
        val stamped = incoming.normalized().copy(serverReceivedAt = now)
        val existing = findProgressUnlocked(stamped.episodeKey)
        if (existing == null) {
            save(stamped)
            return ApplyResult(ProgressWriteResponse(accepted = true, stored = stamped), changed = true)
        }
        val winner = ProgressResolver.pick(existing, stamped)
        val incomingWins = ProgressResolver.sameClientWrite(winner, stamped)
        if (!incomingWins) {
            return ApplyResult(ProgressWriteResponse(accepted = false, stored = existing), changed = false)
        }
        val changed = !ProgressResolver.sameClientWrite(existing, stamped)
        if (changed) save(stamped)
        ApplyResult(
            ProgressWriteResponse(accepted = true, stored = if (changed) stamped else existing),
            changed = changed,
        )
    }

    private fun findProgressUnlocked(episodeKey: String): EpisodeProgress? {
        connection.prepareStatement("SELECT * FROM progress WHERE episode_key = ?").use { statement ->
            statement.setString(1, episodeKey)
            statement.executeQuery().use { rows ->
                return if (rows.next()) rows.toProgress() else null
            }
        }
    }

    private fun save(progress: EpisodeProgress) {
        connection.prepareStatement(
            """
            INSERT INTO progress(
              episode_key, podcast_guid, episode_guid, feed_url, position_ms, duration_ms,
              updated_at, device_id, status, server_received_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(episode_key) DO UPDATE SET
              podcast_guid = excluded.podcast_guid,
              episode_guid = excluded.episode_guid,
              feed_url = excluded.feed_url,
              position_ms = excluded.position_ms,
              duration_ms = excluded.duration_ms,
              updated_at = excluded.updated_at,
              device_id = excluded.device_id,
              status = excluded.status,
              server_received_at = excluded.server_received_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, progress.episodeKey)
            statement.setString(2, progress.podcastGuid)
            statement.setString(3, progress.episodeGuid)
            statement.setString(4, progress.feedUrl)
            statement.setLong(5, progress.positionMs)
            statement.setLong(6, progress.durationMs)
            statement.setLong(7, progress.updatedAt)
            statement.setString(8, progress.deviceId)
            statement.setString(9, progress.status.name)
            statement.setLong(10, progress.serverReceivedAt ?: 0)
            statement.executeUpdate()
        }
    }

    fun getPlaylist(): ListeningPlaylist? = synchronized(lock) {
        connection.prepareStatement("SELECT document FROM playlist WHERE id = 'default'").use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                NahtlosJson.json.decodeFromString<ListeningPlaylist>(rows.getString(1))
            }
        }
    }

    fun applyPlaylist(incoming: ListeningPlaylist, now: Long): PlaylistApply = synchronized(lock) {
        val stamped = incoming.copy(serverReceivedAt = now)
        val existing = getPlaylistUnlocked()
        if (existing == null || stamped.updatedAt > existing.updatedAt) {
            savePlaylist(stamped)
            return PlaylistApply(PlaylistWriteResponse(true, stamped), changed = true)
        }
        if (stamped.updatedAt == existing.updatedAt && stamped.deviceId == existing.deviceId) {
            return PlaylistApply(PlaylistWriteResponse(true, existing), changed = false)
        }
        PlaylistApply(PlaylistWriteResponse(false, existing), changed = false)
    }

    private fun getPlaylistUnlocked(): ListeningPlaylist? {
        connection.prepareStatement("SELECT document FROM playlist WHERE id = 'default'").use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                return NahtlosJson.json.decodeFromString<ListeningPlaylist>(rows.getString(1))
            }
        }
    }

    private fun savePlaylist(playlist: ListeningPlaylist) {
        connection.prepareStatement(
            """
            INSERT INTO playlist(id, document, updated_at, server_received_at)
            VALUES ('default', ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              document = excluded.document,
              updated_at = excluded.updated_at,
              server_received_at = excluded.server_received_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, NahtlosJson.json.encodeToString(ListeningPlaylist.serializer(), playlist))
            statement.setLong(2, playlist.updatedAt)
            statement.setLong(3, playlist.serverReceivedAt ?: 0)
            statement.executeUpdate()
        }
    }

    fun insertDevice(id: String, name: String, token: String, now: Long) = synchronized(lock) {
        connection.prepareStatement(
            "INSERT INTO devices(id, name, token_hash, created_at, last_seen_at, revoked) VALUES (?, ?, ?, ?, NULL, 0)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, name)
            statement.setString(3, EpisodeKeys.sha256(token))
            statement.setLong(4, now)
            statement.executeUpdate()
        }
    }

    fun listDevices(): List<DeviceRow> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT id, name, created_at, last_seen_at, revoked FROM devices ORDER BY created_at DESC",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                val list = mutableListOf<DeviceRow>()
                while (rows.next()) {
                    list += DeviceRow(
                        id = rows.getString(1),
                        name = rows.getString(2),
                        createdAt = rows.getLong(3),
                        lastSeenAt = rows.getLong(4).takeIf { !rows.wasNull() },
                        revoked = rows.getInt(5) != 0,
                    )
                }
                list
            }
        }
    }

    fun revokeDevice(id: String): Boolean = synchronized(lock) {
        connection.prepareStatement("UPDATE devices SET revoked = 1 WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeUpdate() > 0
        }
    }

    /** Returns the device id when [token] matches a live device, and bumps last_seen_at. */
    fun touchDevice(token: String, now: Long): String? = synchronized(lock) {
        val hash = EpisodeKeys.sha256(token)
        val id = connection.prepareStatement(
            "SELECT id FROM devices WHERE token_hash = ? AND revoked = 0",
        ).use { statement ->
            statement.setString(1, hash)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: return null
        connection.prepareStatement("UPDATE devices SET last_seen_at = ? WHERE id = ?").use { statement ->
            statement.setLong(1, now)
            statement.setString(2, id)
            statement.executeUpdate()
        }
        id
    }

    fun purgeExpiredPairings(now: Long) = synchronized(lock) {
        connection.prepareStatement(
            "DELETE FROM pairings WHERE expires_at < ? AND (device_token IS NULL OR consumed = 1)",
        ).use { statement ->
            statement.setLong(1, now)
            statement.executeUpdate()
        }
    }

    fun pairingCodeInUse(code: String, now: Long): Boolean = synchronized(lock) {
        connection.prepareStatement(
            "SELECT 1 FROM pairings WHERE code = ? AND consumed = 0 AND expires_at >= ?",
        ).use { statement ->
            statement.setString(1, code)
            statement.setLong(2, now)
            statement.executeQuery().use { rows -> rows.next() }
        }
    }

    fun insertPairing(id: String, code: String, now: Long, expiresAt: Long) = synchronized(lock) {
        connection.prepareStatement(
            "INSERT INTO pairings(pairing_id, code, created_at, expires_at, consumed) VALUES (?, ?, ?, ?, 0)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, code)
            statement.setLong(3, now)
            statement.setLong(4, expiresAt)
            statement.executeUpdate()
        }
    }

    fun findPairingByCode(code: String): PairingRow? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT pairing_id, code, expires_at, device_name, device_id, device_token, consumed FROM pairings WHERE code = ? AND consumed = 0",
        ).use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toPairing() else null }
        }
    }

    fun findPairing(id: String): PairingRow? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT pairing_id, code, expires_at, device_name, device_id, device_token, consumed FROM pairings WHERE pairing_id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toPairing() else null }
        }
    }

    fun approvePairing(pairingId: String, deviceId: String, name: String, token: String, expiresAt: Long) = synchronized(lock) {
        connection.prepareStatement(
            """
            UPDATE pairings
            SET device_id = ?, device_name = ?, device_token = ?, expires_at = ?
            WHERE pairing_id = ? AND consumed = 0 AND device_token IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setString(2, name)
            statement.setString(3, token)
            statement.setLong(4, expiresAt)
            statement.setString(5, pairingId)
            statement.executeUpdate() > 0
        }
    }

    /** Returns the one-time token and forgets it. */
    fun consumePairingToken(pairingId: String): PairingRow? = synchronized(lock) {
        val row = connection.prepareStatement(
            "SELECT pairing_id, code, expires_at, device_name, device_id, device_token, consumed FROM pairings WHERE pairing_id = ?",
        ).use { statement ->
            statement.setString(1, pairingId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toPairing() else null }
        } ?: return null
        if (row.deviceToken != null && row.consumed == 0) {
            connection.prepareStatement(
                "UPDATE pairings SET consumed = 1, device_token = NULL WHERE pairing_id = ?",
            ).use { statement ->
                statement.setString(1, pairingId)
                statement.executeUpdate()
            }
        }
        row
    }

    fun purgeWalletRelay(now: Long) = synchronized(lock) {
        connection.prepareStatement("DELETE FROM wallet_relay WHERE expires_at < ?").use { statement ->
            statement.setLong(1, now)
            statement.executeUpdate()
        }
    }

    fun openWalletRelay(sessionId: String, recipientDeviceId: String, now: Long, expiresAt: Long) = synchronized(lock) {
        connection.prepareStatement(
            "INSERT INTO wallet_relay(session_id, recipient_device_id, sender_device_id, ciphertext, created_at, expires_at) VALUES (?, ?, NULL, NULL, ?, ?)",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, recipientDeviceId)
            statement.setLong(3, now)
            statement.setLong(4, expiresAt)
            statement.executeUpdate()
        }
    }

    /** Stores ciphertext once. The plaintext never has a column. */
    fun storeWalletCipher(sessionId: String, senderDeviceId: String?, ciphertext: String, now: Long): Boolean = synchronized(lock) {
        val row = walletRow(sessionId) ?: return false
        if (row.expiresAt < now || row.ciphertext != null) return false
        if (senderDeviceId != null && senderDeviceId == row.recipientDeviceId) return false
        connection.prepareStatement(
            "UPDATE wallet_relay SET ciphertext = ?, sender_device_id = ? WHERE session_id = ? AND ciphertext IS NULL",
        ).use { statement ->
            statement.setString(1, ciphertext)
            statement.setString(2, senderDeviceId)
            statement.setString(3, sessionId)
            statement.executeUpdate() > 0
        }
    }

    fun takeWalletCipher(sessionId: String, recipientDeviceId: String, now: Long): WalletTake = synchronized(lock) {
        val row = walletRow(sessionId) ?: return WalletTake(WalletTake.GONE, null, null, 0)
        if (row.recipientDeviceId != recipientDeviceId) return WalletTake(WalletTake.FORBIDDEN, null, null, row.expiresAt)
        if (row.expiresAt < now) {
            deleteWallet(sessionId)
            return WalletTake(WalletTake.EXPIRED, null, null, row.expiresAt)
        }
        if (row.ciphertext == null) return WalletTake(WalletTake.PENDING, null, null, row.expiresAt)
        deleteWallet(sessionId)
        WalletTake(WalletTake.READY, row.ciphertext, row.senderDeviceId, row.expiresAt)
    }

    fun deviceName(id: String): String? = synchronized(lock) {
        connection.prepareStatement("SELECT name FROM devices WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    /** Test helper. Concatenates stored relay rows so tests can see that the secret is absent. */
    fun walletSnapshot(): String = synchronized(lock) {
        connection.prepareStatement(
            "SELECT session_id, recipient_device_id, IFNULL(sender_device_id, ''), IFNULL(ciphertext, '') FROM wallet_relay",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                val text = StringBuilder()
                while (rows.next()) {
                    text.append(rows.getString(1)).append('|')
                    text.append(rows.getString(2)).append('|')
                    text.append(rows.getString(3)).append('|')
                    text.append(rows.getString(4)).append('\n')
                }
                text.toString()
            }
        }
    }

    private fun walletRow(sessionId: String): WalletRow? {
        return connection.prepareStatement(
            "SELECT session_id, recipient_device_id, sender_device_id, ciphertext, expires_at FROM wallet_relay WHERE session_id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                WalletRow(
                    sessionId = rows.getString(1),
                    recipientDeviceId = rows.getString(2),
                    senderDeviceId = rows.getString(3),
                    ciphertext = rows.getString(4),
                    expiresAt = rows.getLong(5),
                )
            }
        }
    }

    private fun deleteWallet(sessionId: String) {
        connection.prepareStatement("DELETE FROM wallet_relay WHERE session_id = ?").use { statement ->
            statement.setString(1, sessionId)
            statement.executeUpdate()
        }
    }

    fun listDiagnose(): List<DiagnoseNote> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT device_id, updated_at, body FROM diagnose ORDER BY updated_at DESC",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                val list = mutableListOf<DiagnoseNote>()
                while (rows.next()) {
                    list += DiagnoseNote(rows.getString(1), rows.getLong(2), rows.getString(3))
                }
                list
            }
        }
    }

    fun saveDiagnose(deviceId: String, updatedAt: Long, body: String) {
        synchronized(lock) {
        val id = deviceId.take(128)
        val existing = connection.prepareStatement("SELECT updated_at FROM diagnose WHERE device_id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
        if (existing != null && existing > updatedAt) return@synchronized
        connection.prepareStatement(
            """
            INSERT INTO diagnose(device_id, updated_at, body) VALUES (?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET updated_at = excluded.updated_at, body = excluded.body
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.setLong(2, updatedAt)
            statement.setString(3, body.take(20_000))
            statement.executeUpdate()
        }
        }
    }

    fun listBoosts(since: Long?): List<BoostSyncItem> = synchronized(lock) {
        val sql = if (since == null) {
            "SELECT id, at_ms, updated_at, total_sats, podcast, episode, recipients_json FROM boosts ORDER BY at_ms DESC"
        } else {
            "SELECT id, at_ms, updated_at, total_sats, podcast, episode, recipients_json FROM boosts WHERE updated_at > ? ORDER BY at_ms DESC"
        }
        connection.prepareStatement(sql).use { statement ->
            if (since != null) statement.setLong(1, since)
            statement.executeQuery().use { rows ->
                val list = mutableListOf<BoostSyncItem>()
                while (rows.next()) list += rows.toBoost()
                list
            }
        }
    }

    fun upsertBoost(incoming: BoostSyncItem, deviceId: String): BoostSyncItem = synchronized(lock) {
        val current = boostById(incoming.id)
        val chosen = BoostSync.prefer(current, incoming)
        if (current != null && chosen.updatedAt == current.updatedAt && chosen.totalSats == current.totalSats && chosen.recipients == current.recipients) {
            return current
        }
        connection.prepareStatement(
            """
            INSERT INTO boosts(id, at_ms, updated_at, total_sats, podcast, episode, recipients_json, device_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              at_ms = excluded.at_ms,
              updated_at = excluded.updated_at,
              total_sats = excluded.total_sats,
              podcast = excluded.podcast,
              episode = excluded.episode,
              recipients_json = excluded.recipients_json,
              device_id = excluded.device_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, chosen.id)
            statement.setLong(2, chosen.atMs)
            statement.setLong(3, chosen.updatedAt)
            statement.setLong(4, chosen.totalSats)
            statement.setString(5, chosen.podcast.take(200))
            statement.setString(6, chosen.episode.take(200))
            statement.setString(7, NahtlosJson.json.encodeToString(ListSerializer(BoostRecipientSync.serializer()), chosen.recipients))
            statement.setString(8, deviceId.take(128))
            statement.executeUpdate()
        }
        chosen
    }

    private fun boostById(id: String): BoostSyncItem? =
        connection.prepareStatement(
            "SELECT id, at_ms, updated_at, total_sats, podcast, episode, recipients_json FROM boosts WHERE id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toBoost() else null }
        }

    private fun java.sql.ResultSet.toBoost(): BoostSyncItem = BoostSyncItem(
        id = getString(1),
        atMs = getLong(2),
        updatedAt = getLong(3),
        totalSats = getLong(4),
        podcast = getString(5),
        episode = getString(6),
        recipients = NahtlosJson.json.decodeFromString<List<de.jenz.nahtlos.sync.BoostRecipientSync>>(getString(7)),
    )

    fun saveNostrIdentity(pubkey: String, name: String, picture: String?, deviceId: String, now: Long) = synchronized(lock) {
        connection.prepareStatement(
            """
            INSERT INTO nostr_identity(id, pubkey, name, picture, updated_at, device_id)
            VALUES (1, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              pubkey = excluded.pubkey,
              name = excluded.name,
              picture = excluded.picture,
              updated_at = excluded.updated_at,
              device_id = excluded.device_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, pubkey)
            statement.setString(2, name)
            statement.setString(3, picture)
            statement.setLong(4, now)
            statement.setString(5, deviceId)
            statement.executeUpdate()
        }
    }

    fun readNostrIdentity(): NostrIdentityBody? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT pubkey, name, picture, updated_at FROM nostr_identity WHERE id = 1",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                NostrIdentityBody(
                    pubkey = rows.getString(1),
                    name = rows.getString(2),
                    picture = rows.getString(3),
                    updatedAt = rows.getLong(4),
                )
            }
        }
    }

    fun insertNostrDraft(
        id: String,
        kind: Int,
        content: String,
        tagsJson: String,
        createdAt: Long,
        deviceId: String,
        now: Long,
        expiresAt: Long,
    ) = synchronized(lock) {
        connection.prepareStatement(
            """
            INSERT INTO nostr_sign(id, kind, content, tags_json, created_at, status, event_json, error, requester_device_id, opened_at, expires_at)
            VALUES (?, ?, ?, ?, ?, 'pending', NULL, NULL, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.setInt(2, kind)
            statement.setString(3, content)
            statement.setString(4, tagsJson)
            statement.setLong(5, createdAt)
            statement.setString(6, deviceId)
            statement.setLong(7, now)
            statement.setLong(8, expiresAt)
            statement.executeUpdate()
        }
    }

    fun listPendingNostr(now: Long): List<NostrSignStatus> = synchronized(lock) {
        markExpiredNostr(now)
        connection.prepareStatement(
            """
            SELECT id, kind, content, tags_json, created_at, expires_at
            FROM nostr_sign WHERE status = 'pending' AND expires_at >= ? ORDER BY opened_at
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, now)
            statement.executeQuery().use { rows ->
                val list = mutableListOf<NostrSignStatus>()
                while (rows.next()) {
                    val tags = runCatching {
                        NahtlosJson.json.decodeFromString(nostrTagSerializer, rows.getString(4))
                    }.getOrDefault(emptyList())
                    val expires = rows.getLong(6)
                    list += NostrSignStatus(
                        id = rows.getString(1),
                        status = NostrSignStatus.PENDING,
                        kind = rows.getInt(2),
                        content = rows.getString(3),
                        tags = tags,
                        createdAt = rows.getLong(5),
                        expiresIn = ((expires - now) / 1000L).toInt().coerceAtLeast(0),
                    )
                }
                list
            }
        }
    }

    fun readNostrSign(id: String, now: Long): NostrSignStatus? = synchronized(lock) {
        markExpiredNostr(now)
        connection.prepareStatement(
            "SELECT id, status, kind, content, tags_json, created_at, expires_at, event_json, error FROM nostr_sign WHERE id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                val tags = runCatching {
                    NahtlosJson.json.decodeFromString(nostrTagSerializer, rows.getString(5))
                }.getOrDefault(emptyList())
                val expires = rows.getLong(7)
                NostrSignStatus(
                    id = rows.getString(1),
                    status = rows.getString(2),
                    kind = rows.getInt(3),
                    content = rows.getString(4),
                    tags = tags,
                    createdAt = rows.getLong(6),
                    expiresIn = ((expires - now) / 1000L).toInt().coerceAtLeast(0),
                    event = rows.getString(8),
                    error = rows.getString(9),
                )
            }
        }
    }

    /** true, wenn die Signatur neu liegt oder dieselbe schon da war. */
    fun storeNostrEvent(id: String, eventJson: String, now: Long): Boolean = synchronized(lock) {
        markExpiredNostr(now)
        val current = readNostrSignLocked(id) ?: return false
        if (current.status == NostrSignStatus.SIGNED) return current.event == eventJson
        if (current.status != NostrSignStatus.PENDING) return false
        connection.prepareStatement(
            "UPDATE nostr_sign SET status = 'signed', event_json = ?, error = NULL WHERE id = ? AND status = 'pending'",
        ).use { statement ->
            statement.setString(1, eventJson)
            statement.setString(2, id)
            statement.executeUpdate() > 0
        }
    }

    fun failNostrSign(id: String, error: String, now: Long): Boolean = synchronized(lock) {
        markExpiredNostr(now)
        connection.prepareStatement(
            "UPDATE nostr_sign SET status = 'failed', error = ? WHERE id = ? AND status = 'pending' AND expires_at >= ?",
        ).use { statement ->
            statement.setString(1, error.take(200))
            statement.setString(2, id)
            statement.setLong(3, now)
            statement.executeUpdate() > 0
        }
    }

    private fun readNostrSignLocked(id: String): NostrSignStatus? =
        connection.prepareStatement(
            "SELECT id, status, kind, content, tags_json, created_at, expires_at, event_json, error FROM nostr_sign WHERE id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                NostrSignStatus(
                    id = rows.getString(1),
                    status = rows.getString(2),
                    kind = rows.getInt(3),
                    content = rows.getString(4),
                    createdAt = rows.getLong(6),
                    event = rows.getString(8),
                    error = rows.getString(9),
                )
            }
        }

    private fun markExpiredNostr(now: Long) {
        connection.prepareStatement(
            "UPDATE nostr_sign SET status = 'expired' WHERE status = 'pending' AND expires_at < ?",
        ).use { statement ->
            statement.setLong(1, now)
            statement.executeUpdate()
        }
    }

    override fun close() {
        synchronized(lock) { connection.close() }
    }

    private fun java.sql.ResultSet.toProgress(): EpisodeProgress = EpisodeProgress(
        episodeKey = getString("episode_key"),
        podcastGuid = getString("podcast_guid"),
        episodeGuid = getString("episode_guid"),
        feedUrl = getString("feed_url"),
        positionMs = getLong("position_ms"),
        durationMs = getLong("duration_ms"),
        updatedAt = getLong("updated_at"),
        deviceId = getString("device_id"),
        status = ProgressStatus.valueOf(getString("status")),
        serverReceivedAt = getLong("server_received_at"),
    )
}

data class ApplyResult(
    val response: ProgressWriteResponse,
    val changed: Boolean,
)

data class PlaylistApply(
    val response: PlaylistWriteResponse,
    val changed: Boolean,
)

data class DeviceRow(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long?,
    val revoked: Boolean,
)

data class WalletTake(
    val status: String,
    val ciphertext: String?,
    val senderDeviceId: String?,
    val expiresAt: Long,
) {
    companion object {
        const val PENDING = "pending"
        const val READY = "ready"
        const val EXPIRED = "expired"
        const val GONE = "gone"
        const val FORBIDDEN = "forbidden"
    }
}

private data class WalletRow(
    val sessionId: String,
    val recipientDeviceId: String,
    val senderDeviceId: String?,
    val ciphertext: String?,
    val expiresAt: Long,
)

data class PairingRow(
    val pairingId: String,
    val code: String,
    val expiresAt: Long,
    val deviceName: String?,
    val deviceId: String?,
    val deviceToken: String?,
    val consumed: Int,
)

private val nostrTagSerializer = ListSerializer(ListSerializer(String.serializer()))

private fun java.sql.ResultSet.toPairing(): PairingRow = PairingRow(
    pairingId = getString(1),
    code = getString(2),
    expiresAt = getLong(3),
    deviceName = getString(4),
    deviceId = getString(5),
    deviceToken = getString(6),
    consumed = getInt(7),
)

fun constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
