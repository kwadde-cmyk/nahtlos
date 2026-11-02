package de.jenz.nahtlos.automotive

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import de.jenz.nahtlos.core.CrashLog
import de.jenz.nahtlos.core.sync.RealtimeSync
import javax.inject.Inject

@HiltAndroidApp
class AutoApp : Application() {
    @Inject lateinit var realtime: RealtimeSync

    override fun onCreate() {
        CrashLog.install(this)
        super.onCreate()
        realtime.start()
    }
}
