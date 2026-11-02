package de.jenz.nahtlos

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import de.jenz.nahtlos.core.CrashLog
import de.jenz.nahtlos.core.sync.RealtimeSync
import javax.inject.Inject

@HiltAndroidApp
class NahtlosApp : Application() {
    @Inject lateinit var realtime: RealtimeSync

    override fun onCreate() {
        CrashLog.install(this)
        super.onCreate()
        realtime.start()
    }
}
