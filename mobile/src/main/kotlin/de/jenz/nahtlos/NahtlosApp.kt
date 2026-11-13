package de.jenz.nahtlos

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import de.jenz.nahtlos.core.AppLog
import de.jenz.nahtlos.core.CrashLog
import de.jenz.nahtlos.core.Payments
import de.jenz.nahtlos.core.artwork.ArtworkStore
import de.jenz.nahtlos.core.nostr.AmberSigner
import de.jenz.nahtlos.core.nostr.AmberSignerHolder
import de.jenz.nahtlos.core.sync.RealtimeSync
import javax.inject.Inject

@HiltAndroidApp
class NahtlosApp : Application() {
    @Inject lateinit var realtime: RealtimeSync

    override fun onCreate() {
        Payments.enabled = BuildConfig.PAYMENTS_ENABLED
        CrashLog.install(this)
        AppLog.install(this)
        ArtworkStore.installCoil(this)
        super.onCreate()
        AmberSignerHolder.signer = AmberSigner(this)
        realtime.start()
    }
}
