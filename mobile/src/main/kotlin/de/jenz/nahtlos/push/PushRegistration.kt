package de.jenz.nahtlos.push

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.EntryPointAccessors
import de.jenz.nahtlos.BuildConfig
import de.jenz.nahtlos.core.nostr.NostrPhoneSigner
import de.jenz.nahtlos.core.nostr.NostrSignerEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Push ist nur ein Weckruf. Ohne google-services.json fehlt google_app_id,
 * dann passiert hier nichts und der bisherige WorkManager-Weg bleibt.
 */
object PushRegistration {
    fun start(app: Application) {
        if (!BuildConfig.FCM_ENABLED || !configured(app)) return
        if (FirebaseApp.getApps(app).isEmpty()) FirebaseApp.initializeApp(app)
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) return@addOnCompleteListener
            val token = task.result?.trim().orEmpty()
            if (token.isEmpty()) return@addOnCompleteListener
            signer(app).registerPushToken(token)
        }
    }

    fun configured(app: Application): Boolean =
        app.resources.getIdentifier("google_app_id", "string", app.packageName) != 0

    private fun signer(app: Application): NostrPhoneSigner =
        EntryPointAccessors.fromApplication(app, NostrSignerEntryPoint::class.java).signer()
}

class SignMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        if (!BuildConfig.FCM_ENABLED) return
        if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
        super.onCreate()
    }

    override fun onNewToken(token: String) {
        PushRegistration.start(application)
        signer().registerPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["what"] != "sign") return
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(25_000) { signer().pass(interactive = false) }
        }
    }

    private fun signer(): NostrPhoneSigner =
        EntryPointAccessors.fromApplication(applicationContext, NostrSignerEntryPoint::class.java).signer()
}
