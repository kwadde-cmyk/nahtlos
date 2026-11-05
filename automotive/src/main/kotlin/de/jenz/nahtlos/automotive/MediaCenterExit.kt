package de.jenz.nahtlos.automotive

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Öffnet das Mediencenter auf diesem Media-Dienst und schließt die Einstellungen. */
object MediaCenterExit {
    const val ACTION = "android.car.intent.action.MEDIA_TEMPLATE"
    const val EXTRA_COMPONENT = "android.car.intent.extra.MEDIA_COMPONENT"
    const val SERVICE_CLASS = "de.jenz.nahtlos.core.playback.PodcastMediaLibraryService"

    fun component(packageName: String): String = "$packageName/$SERVICE_CLASS"

    fun templateIntent(packageName: String): Intent =
        Intent(ACTION).apply {
            putExtra(EXTRA_COMPONENT, ComponentName(packageName, SERVICE_CLASS).flattenToString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun open(context: Context) {
        val music = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOf(templateIntent(context.packageName), music)) {
            try {
                context.startActivity(intent)
                break
            } catch (_: ActivityNotFoundException) {
                continue
            }
        }
        (context as? Activity)?.finish()
    }
}
