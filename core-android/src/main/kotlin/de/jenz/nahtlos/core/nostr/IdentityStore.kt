package de.jenz.nahtlos.core.nostr

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jenz.nahtlos.nostr.NostrConnectOffer
import de.jenz.nahtlos.nostr.NostrException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Öffentlicher Schlüssel und, nur im Auto, der Sitzungsschlüssel für NIP-46.
 * Der Sitzungsschlüssel ist nicht der Schlüssel des Hörers. Beides liegt
 * verschlüsselt und geht nicht ins Log.
 */
data class StoredIdentity(
    val userPubkey: String = "",
    val signerPackage: String? = null,
    val clientSecret: String? = null,
    val remoteSigner: String? = null,
    val name: String = "",
    val picture: String? = null,
) {
    val amber: Boolean get() = !signerPackage.isNullOrBlank() && userPubkey.length == 64
    val bunker: Boolean get() = !clientSecret.isNullOrBlank() && !remoteSigner.isNullOrBlank() && userPubkey.length == 64
    val loggedIn: Boolean get() = userPubkey.length == 64

    override fun toString(): String = "identity:${userPubkey.take(8)} amber=$amber bunker=$bunker"
}

@Singleton
class IdentityStore @Inject constructor(@ApplicationContext context: Context) {
    private val app = context.applicationContext
    private val lock = Any()
    private val prefs: SharedPreferences by lazy { openEncrypted() }

    fun read(): StoredIdentity = synchronized(lock) {
        StoredIdentity(
            userPubkey = prefs.getString(KEY_PUB, "").orEmpty().lowercase(),
            signerPackage = prefs.getString(KEY_PKG, null)?.takeIf { it.isNotBlank() },
            clientSecret = prefs.getString(KEY_CLIENT, null)?.takeIf { it.isNotBlank() },
            remoteSigner = prefs.getString(KEY_REMOTE, null)?.takeIf { it.isNotBlank() },
            name = prefs.getString(KEY_NAME, "").orEmpty(),
            picture = prefs.getString(KEY_PIC, null)?.takeIf { it.startsWith("https://") },
        )
    }

    fun saveAmber(pubkey: String, signerPackage: String) = synchronized(lock) {
        prefs.edit()
            .putString(KEY_PUB, pubkey.lowercase())
            .putString(KEY_PKG, signerPackage)
            .remove(KEY_CLIENT)
            .remove(KEY_REMOTE)
            .remove(KEY_PENDING)
            .apply()
    }

    fun savePending(offer: NostrConnectOffer) = synchronized(lock) {
        prefs.edit().putString(KEY_PENDING, offer.clientSecret).apply()
    }

    fun commitCar(userPubkey: String, offer: NostrConnectOffer, remoteSigner: String) = synchronized(lock) {
        prefs.edit()
            .putString(KEY_PUB, userPubkey.lowercase())
            .putString(KEY_CLIENT, offer.clientSecret)
            .putString(KEY_REMOTE, remoteSigner.lowercase())
            .remove(KEY_PKG)
            .remove(KEY_PENDING)
            .apply()
    }

    /** Nur der öffentliche Schlüssel. Fürs Auto, vom Server geholt. */
    fun savePublic(pubkey: String, name: String, picture: String?) = synchronized(lock) {
        prefs.edit()
            .putString(KEY_PUB, pubkey.lowercase())
            .putString(KEY_NAME, name.take(80))
            .putString(KEY_PIC, picture?.takeIf { it.startsWith("https://") })
            .remove(KEY_CLIENT)
            .remove(KEY_REMOTE)
            .remove(KEY_PENDING)
            .apply()
    }

    fun saveProfile(name: String, picture: String?) = synchronized(lock) {
        prefs.edit()
            .putString(KEY_NAME, name.take(80))
            .putString(KEY_PIC, picture?.takeIf { it.startsWith("https://") })
            .apply()
    }

    fun clearPending() = synchronized(lock) {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    fun logout() = synchronized(lock) {
        prefs.edit().clear().apply()
    }

    private fun openEncrypted(): SharedPreferences = try {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app,
            "nahtlos.nostr",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Exception) {
        throw NostrException("Die sichere Ablage ist nicht verfügbar. Es wurde kein Schlüssel gespeichert.")
    }

    private companion object {
        const val KEY_PUB = "pub"
        const val KEY_PKG = "pkg"
        const val KEY_CLIENT = "client"
        const val KEY_REMOTE = "remote"
        const val KEY_NAME = "name"
        const val KEY_PIC = "picture"
        const val KEY_PENDING = "pending"
    }
}
