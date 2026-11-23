package com.kisuro.fireplace.tv.data

import android.content.Context

/**
 * Persists the last-played fireplace id across process death / app restarts.
 */
object LastPlayedStore {
    private const val PREFS = "fireplace_prefs"
    private const val KEY_LAST_VIDEO_ID = "last_video_id"

    fun getVideoId(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_VIDEO_ID, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setVideoId(context: Context, videoId: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_VIDEO_ID, videoId)
            .apply()
    }

    fun resolveVideo(context: Context): FireplaceVideo {
        val browse = VideoCatalog.browseVideos(context)
        val saved = getVideoId(context)?.let { id ->
            browse.firstOrNull { it.id == id }
        }
        if (saved != null) return saved
        browse.firstOrNull()?.let { return it }
        // Favorites filter empty → fall back to full ordered strip for hero restore.
        return VideoCatalog.orderedVisible(context).firstOrNull()
            ?: VideoCatalog.videos.first()
    }
}
