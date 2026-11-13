package de.jenz.nahtlos.core.nostr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import de.jenz.nahtlos.nostr.NostrDraft
import de.jenz.nahtlos.nostr.NostrException
import de.jenz.nahtlos.nostr.NostrKeys
import de.jenz.nahtlos.nostr.PodcastNostr
import de.jenz.nahtlos.wallet.Bytes
import de.jenz.nahtlos.wallet.NostrEvent
import de.jenz.nahtlos.wallet.SignedEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * NIP-55. Der Schlüssel bleibt in Amber. Nahtlos sieht nur die Signatur.
 * Zuerst der Content-Provider, falls die Erlaubnis schon dauerhaft gilt,
 * sonst die Activity.
 */
class AmberSigner(private val context: Context) {
    fun installed(): Boolean =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")), 0).isNotEmpty()

    fun open(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    suspend fun login(): Pair<String, String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            putExtra("type", "get_public_key")
            putExtra("permissions", PERMISSIONS)
        }
        val data = SignerBridgeActivity.request(context, intent)
        if (data.rejected()) throw NostrException("Amber hat die Anmeldung abgelehnt.")
        val raw = data.getStringExtra("result")?.trim()?.lowercase().orEmpty()
        if (!raw.matches(HEX_64)) throw NostrException("Amber hat keinen öffentlichen Schlüssel geliefert.")
        val pkg = data.getStringExtra("package")?.takeIf { it.isNotBlank() }
            ?: throw NostrException("Amber hat kein Paket genannt.")
        return raw to pkg
    }

    suspend fun sign(signerPackage: String, pubkey: String, draft: NostrDraft): SignedEvent {
        val id = Bytes.hex(
            Bytes.sha256(NostrEvent.idPreimage(pubkey, draft.createdAt, draft.kind, draft.tags, draft.content).toByteArray()),
        )
        val unsigned = unsignedJson(id, pubkey, draft)
        quietly(signerPackage, unsigned, pubkey, id, draft)?.let { return it }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$unsigned")).apply {
            `package` = signerPackage
            putExtra("type", "sign_event")
            putExtra("id", id)
            putExtra("current_user", pubkey)
        }
        val data = SignerBridgeActivity.request(context, intent)
        if (data.rejected()) throw NostrException("Amber hat die Signatur abgelehnt.")
        return signed(data.getStringExtra("event"), data.getStringExtra("result"), id, pubkey, draft)
    }

    private suspend fun quietly(
        signerPackage: String,
        eventJson: String,
        pubkey: String,
        id: String,
        draft: NostrDraft,
    ): SignedEvent? =
        ask(signerPackage, "SIGN_EVENT", arrayOf(eventJson, "", pubkey))?.let { row ->
            runCatching { signed(row["event"], row["result"], id, pubkey, draft) }.getOrNull()
        }

    private fun signed(eventJson: String?, signature: String?, expectedId: String?, pubkey: String, draft: NostrDraft?): SignedEvent {
        val parsed = eventJson?.let { runCatching { json.decodeFromString(SignedEventJson.serializer(), it).toEvent() }.getOrNull() }
        val event = parsed ?: draft?.let { body ->
            val id = expectedId ?: return@let null
            val sig = signature?.trim()?.lowercase()?.takeIf { it.matches(HEX_128) } ?: return@let null
            SignedEvent(id, pubkey, body.createdAt, body.kind, body.tags, body.content, sig)
        } ?: throw NostrException("Amber hat nichts Signiertes zurückgegeben.")
        if (!NostrKeys.valid(event) || event.pubkey != pubkey) throw NostrException("Amber hat ein anderes Event zurückgegeben.")
        if (expectedId != null && event.id != expectedId) throw NostrException("Amber hat ein anderes Event zurückgegeben.")
        return event
    }

    private suspend fun ask(signerPackage: String, method: String, args: Array<String>): Map<String, String?>? =
        withContext(Dispatchers.IO) {
            val cursor = runCatching {
                context.contentResolver.query(Uri.parse("content://$signerPackage.$method"), args, null, null, null)
            }.getOrNull() ?: return@withContext null
            cursor.use { rows ->
                if (!rows.moveToFirst()) return@withContext null
                if (rows.getColumnIndex("rejected") >= 0 && rows.getString(rows.getColumnIndex("rejected")) == "true") {
                    throw NostrException("Amber hat abgelehnt.")
                }
                rows.columnNames.associateWith { name ->
                    val index = rows.getColumnIndex(name)
                    if (index < 0) null else rows.getString(index)
                }
            }
        }

    private fun unsignedJson(id: String, pubkey: String, draft: NostrDraft): String {
        val tags = draft.tags.joinToString(",") { tag ->
            tag.joinToString(",", prefix = "[", postfix = "]") { NostrEvent.jsonString(it) }
        }
        return buildString {
            append("{\"id\":${NostrEvent.jsonString(id)}")
            append(",\"pubkey\":${NostrEvent.jsonString(pubkey)}")
            append(",\"created_at\":${draft.createdAt}")
            append(",\"kind\":${draft.kind}")
            append(",\"tags\":[$tags]")
            append(",\"content\":${NostrEvent.jsonString(draft.content)}}")
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val HEX_64 = Regex("[0-9a-f]{64}")
        val HEX_128 = Regex("[0-9a-f]{128}")
        const val PERMISSIONS =
            """[{"type":"sign_event","kind":1},{"type":"sign_event","kind":1111},{"type":"sign_event","kind":7},{"type":"sign_event","kind":9734}]"""
    }
}

@kotlinx.serialization.Serializable
private data class SignedEventJson(
    val id: String,
    val pubkey: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toEvent() = SignedEvent(id.lowercase(), pubkey.lowercase(), createdAt, kind, tags, content, sig.lowercase())
}

private fun Intent.rejected(): Boolean =
    getBooleanExtra("rejected", false) || getStringExtra("rejected") == "true"

/**
 * Nimmt das Ergebnis von Amber entgegen. Zeigt selbst nichts.
 */
class SignerBridgeActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val waiting = requestId?.let(pending::remove)
        when {
            waiting == null -> Unit
            result.resultCode != Activity.RESULT_OK || result.data == null ->
                waiting.completeExceptionally(NostrException("Amber hat nicht geantwortet."))
            else -> waiting.complete(result.data!!)
        }
        finish()
    }
    private var requestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_REQUEST)
        requestId = id
        if (savedInstanceState != null) return
        val request = id?.let(requests::remove)
        if (id == null || request == null) {
            finish()
            return
        }
        try {
            launcher.launch(request)
        } catch (_: Exception) {
            pending.remove(id)?.completeExceptionally(NostrException("Amber ist nicht installiert."))
            finish()
        }
    }

    companion object {
        private const val EXTRA_REQUEST = "request"
        private val requests = ConcurrentHashMap<String, Intent>()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Intent>>()

        suspend fun request(context: Context, signerIntent: Intent): Intent {
            val id = UUID.randomUUID().toString()
            val answer = CompletableDeferred<Intent>()
            requests[id] = signerIntent
            pending[id] = answer
            try {
                context.startActivity(
                    Intent(context, SignerBridgeActivity::class.java)
                        .putExtra(EXTRA_REQUEST, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return answer.await()
            } finally {
                requests.remove(id)
                pending.remove(id)
            }
        }
    }
}
