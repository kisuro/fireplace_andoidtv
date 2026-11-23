package com.kisuro.fireplace.tv.data

import android.content.Context

/**
 * Persists independent fireplace-stream mute and ambient nature preferences.
 *
 * Defaults: ambient off; fireplace crackle unmuted; last ambient choice Rain.
 */
object AudioPrefsStore {
    private const val PREFS = "fireplace_prefs"
    private const val KEY_AMBIENT_ENABLED = "ambient_enabled"
    private const val KEY_AMBIENT_SOUND_ID = "ambient_sound_id"
    private const val KEY_AMBIENT_VOLUME = "ambient_volume"
    private const val KEY_FIREPLACE_MUTED = "fireplace_muted"

    private const val DEFAULT_AMBIENT_VOLUME = 0.40f

    fun isAmbientEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AMBIENT_ENABLED, false)
    }

    fun setAmbientEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AMBIENT_ENABLED, enabled).apply()
    }

    fun getAmbientSound(context: Context): AmbientSound {
        return AmbientSound.fromId(prefs(context).getString(KEY_AMBIENT_SOUND_ID, AmbientSound.RAIN.id))
    }

    fun setAmbientSound(context: Context, sound: AmbientSound) {
        prefs(context).edit().putString(KEY_AMBIENT_SOUND_ID, sound.id).apply()
    }

    /** 0f..1f */
    fun getAmbientVolume(context: Context): Float {
        val stored = prefs(context).getFloat(KEY_AMBIENT_VOLUME, DEFAULT_AMBIENT_VOLUME)
        return stored.coerceIn(0f, 1f)
    }

    fun setAmbientVolume(context: Context, volume: Float) {
        prefs(context).edit().putFloat(KEY_AMBIENT_VOLUME, volume.coerceIn(0f, 1f)).apply()
    }

    /** When true, YouTube fireplace crackle is silent; ambient is unaffected. */
    fun isFireplaceMuted(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FIREPLACE_MUTED, false)
    }

    fun setFireplaceMuted(context: Context, muted: Boolean) {
        prefs(context).edit().putBoolean(KEY_FIREPLACE_MUTED, muted).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
