package de.jenz.nahtlos

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import de.jenz.nahtlos.core.sync.RealtimeSync
import javax.inject.Inject

@HiltAndroidApp
class NahtlosApp : Application() {
    @Inject lateinit var realtime: RealtimeSync

    override fun onCreate() {
        super.onCreate()
        realtime.start()
    }
}
