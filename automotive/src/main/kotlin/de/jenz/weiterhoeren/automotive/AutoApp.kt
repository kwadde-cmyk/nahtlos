package de.jenz.weiterhoeren.automotive

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import de.jenz.weiterhoeren.core.sync.RealtimeSync
import javax.inject.Inject

@HiltAndroidApp
class AutoApp : Application() {
    @Inject lateinit var realtime: RealtimeSync

    override fun onCreate() {
        super.onCreate()
        realtime.start()
    }
}
