package com.kisuro.fireplace.tv.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.kisuro.fireplace.tv.data.AmbientSound
import com.kisuro.fireplace.tv.data.AudioPrefsStore

/**
 * Loops one bundled ambient nature sound, independent of YouTube ExoPlayer volume.
 * Off by default; respects [AudioPrefsStore] for choice and volume.
 */
class AmbientSoundPlayer(context: Context) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var activeSound: AmbientSound? = null
    private var wantPlaying: Boolean = false

    fun setPlaying(playing: Boolean) {
        wantPlaying = playing
        sync()
    }

    /** Reload prefs and start/stop/switch as needed. */
    fun sync() {
        val enabled = AudioPrefsStore.isAmbientEnabled(appContext)
        if (!wantPlaying || !enabled) {
            pauseOnly()
            return
        }
        val sound = AudioPrefsStore.getAmbientSound(appContext)
        val volume = AudioPrefsStore.getAmbientVolume(appContext)
        ensurePlayer(sound)
        val mp = player ?: return
        mp.setVolume(volume, volume)
        if (!mp.isPlaying) {
            try {
                mp.start()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "ambient start failed sound=${sound.id}", e)
                release()
            }
        }
    }

    fun release() {
        wantPlaying = false
        player?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
                // already stopped
            }
            release()
        }
        player = null
        activeSound = null
    }

    private fun pauseOnly() {
        player?.let { mp ->
            if (mp.isPlaying) {
                try {
                    mp.pause()
                } catch (_: IllegalStateException) {
                    // ignore
                }
            }
        }
    }

    private fun ensurePlayer(sound: AmbientSound) {
        if (player != null && activeSound == sound) return
        player?.release()
        player = null
        activeSound = null
        try {
            val mp = MediaPlayer.create(appContext, sound.rawResId) ?: run {
                Log.e(TAG, "MediaPlayer.create null for ${sound.id}")
                return
            }
            mp.isLooping = true
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player = mp
            activeSound = sound
        } catch (e: Exception) {
            Log.e(TAG, "failed to create ambient player sound=${sound.id}", e)
            player = null
            activeSound = null
        }
    }

    companion object {
        private const val TAG = "AmbientSound"
    }
}
