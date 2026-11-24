package com.kisuro.fireplace.tv.playback

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * Process-scoped ambient nature player.
 *
 * Keeps the selected ambient looping across Main (browse/hero) and Player so
 * leaving fullscreen does not silence it. Pauses only when the app has no
 * started activities (background / leave app) or the user turns ambient Off.
 */
object AmbientSoundController {
    private var player: AmbientSoundPlayer? = null
    private var startedActivities: Int = 0
    private var registered: Boolean = false

    fun install(app: Application) {
        if (registered) return
        registered = true
        player = AmbientSoundPlayer(app)
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities += 1
                    if (startedActivities == 1) {
                        setForeground(true)
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                    if (startedActivities == 0) {
                        setForeground(false)
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        Log.i(TAG, "installed process-scoped ambient controller")
    }

    /** Reload prefs (enabled / sound / volume) without changing foreground intent. */
    fun sync(context: Context) {
        ensureInstalled(context)
        player?.sync()
    }

    private fun setForeground(foreground: Boolean) {
        val p = player ?: return
        p.setPlaying(foreground)
        Log.i(TAG, "foreground=$foreground started=$startedActivities")
    }

    private fun ensureInstalled(context: Context) {
        if (registered) return
        val app = context.applicationContext as? Application ?: return
        install(app)
    }

    private const val TAG = "AmbientSound"
}
