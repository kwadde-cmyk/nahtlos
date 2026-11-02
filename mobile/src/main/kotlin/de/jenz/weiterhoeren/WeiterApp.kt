package de.jenz.weiterhoeren

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import de.jenz.weiterhoeren.core.sync.RealtimeSync
import javax.inject.Inject

@HiltAndroidApp
class WeiterApp : Application() {
    @Inject lateinit var realtime: RealtimeSync

    override fun onCreate() {
        super.onCreate()
        realtime.start()
    }
}
