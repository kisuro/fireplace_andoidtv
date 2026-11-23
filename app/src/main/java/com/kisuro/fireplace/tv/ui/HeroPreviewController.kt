package com.kisuro.fireplace.tv.ui

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.kisuro.fireplace.tv.data.FireplaceVideo
import com.kisuro.fireplace.tv.extract.OkHttpDownloader
import com.kisuro.fireplace.tv.extract.PlaybackSource
import com.kisuro.fireplace.tv.extract.YoutubeStreamResolver
import com.kisuro.fireplace.tv.playback.FireplacePlayerFactory
import com.kisuro.fireplace.tv.playback.Vp9Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Muted looping preview in the browse hero. Shows the still thumb until the
 * stream is READY; debounces rapid carousel changes.
 */
@UnstableApi
class HeroPreviewController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val playerView: PlayerView,
    private val poster: ImageView,
) {
    private val resolver = YoutubeStreamResolver()
    private var player: ExoPlayer? = null
    private var previewJob: Job? = null
    private var generation: Int = 0
    private var activeVideoId: String? = null
    private var paused: Boolean = false

    init {
        playerView.useController = false
        playerView.setControllerAutoShow(false)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        playerView.isFocusable = false
        playerView.isClickable = false
        playerView.isFocusableInTouchMode = false
        playerView.visibility = View.INVISIBLE
    }

    fun preview(video: FireplaceVideo) {
        if (video.id == activeVideoId && player != null && !paused) {
            return
        }
        activeVideoId = video.id
        poster.setImageResource(video.thumbnailResId)
        poster.visibility = View.VISIBLE
        playerView.visibility = View.INVISIBLE

        previewJob?.cancel()
        val gen = ++generation
        previewJob = lifecycleOwner.lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            if (!isActive || gen != generation || paused) return@launch
            releasePlayerOnly()
            try {
                val resolved = withContext(Dispatchers.IO) {
                    resolver.resolve(video.watchUrl, expectLive = video.isLive)
                }
                if (!isActive || gen != generation || paused || activeVideoId != video.id) return@launch
                bind(
                    resolved.playback,
                    isLive = video.isLive || resolved.isLive,
                    startPositionMs = video.startPositionMs,
                    gen = gen,
                )
            } catch (e: Exception) {
                Log.w(TAG, "hero preview resolve fail id=${video.id}: ${e.message}")
                // Keep still poster — preview is best-effort.
            }
        }
    }

    fun pause() {
        paused = true
        previewJob?.cancel()
        player?.playWhenReady = false
        player?.pause()
    }

    fun resume(video: FireplaceVideo?) {
        paused = false
        if (video == null) return
        // Force a fresh start so we don't show a stale surface after PlayerActivity.
        activeVideoId = null
        preview(video)
    }

    fun release() {
        paused = true
        previewJob?.cancel()
        previewJob = null
        generation++
        activeVideoId = null
        releasePlayerOnly()
        playerView.player = null
        playerView.visibility = View.INVISIBLE
        poster.visibility = View.VISIBLE
    }

    private fun bind(
        source: PlaybackSource,
        isLive: Boolean,
        startPositionMs: Long,
        gen: Int,
    ) {
        if (gen != generation) return
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(OkHttpDownloader.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com",
                ),
            )
        val loadPolicy = DefaultLoadErrorHandlingPolicy(2)
        val mediaSource = when (source) {
            is PlaybackSource.Hls -> HlsMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadPolicy)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(source.url))
            is PlaybackSource.Dash -> DashMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadPolicy)
                .createMediaSource(MediaItem.fromUri(source.url))
            is PlaybackSource.Progressive -> ProgressiveMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadPolicy)
                .createMediaSource(MediaItem.fromUri(source.url))
            is PlaybackSource.Merged -> {
                val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadPolicy)
                MergingMediaSource(
                    progressive.createMediaSource(MediaItem.fromUri(source.videoUrl)),
                    progressive.createMediaSource(MediaItem.fromUri(source.audioUrl)),
                )
            }
        }

        // Small buffers — browse preview, not fullscreen watching.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 15_000, 1_000, 2_000)
            .build()
        val (exo, selector) = FireplacePlayerFactory.create(
            context,
            Vp9Policy.DEMOTE_MTK_VP9,
            loadControl,
        )
        // Cap preview quality so carousel browsing stays snappy on TV SoCs.
        selector.parameters = selector.buildUponParameters()
            .setMaxVideoSize(PREVIEW_MAX_W, PREVIEW_MAX_H)
            .setViewportSize(PREVIEW_MAX_W, PREVIEW_MAX_H, true)
            .build()

        player = exo
        playerView.player = exo
        exo.volume = 0f
        exo.repeatMode = if (isLive) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        var appliedStartSeek = false
        exo.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (gen != generation) return
                    if (playbackState == Player.STATE_READY) {
                        if (!isLive && startPositionMs > 0L && !appliedStartSeek) {
                            val duration = exo.duration
                            val target = if (duration > 0L) {
                                startPositionMs.coerceAtMost(duration.coerceAtLeast(0L))
                            } else {
                                startPositionMs
                            }
                            exo.seekTo(target)
                            appliedStartSeek = true
                            Log.i(TAG, "hero seek startMs=$target")
                        }
                        playerView.visibility = View.VISIBLE
                        poster.visibility = View.INVISIBLE
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (gen != generation) return
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                        !isLive &&
                        startPositionMs > 0L
                    ) {
                        exo.seekTo(startPositionMs)
                        appliedStartSeek = true
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "hero preview error: ${error.errorCodeName}")
                    if (gen != generation) return
                    playerView.visibility = View.INVISIBLE
                    poster.visibility = View.VISIBLE
                }
            },
        )
        exo.setMediaSource(mediaSource)
        exo.playWhenReady = true
        exo.prepare()
        Log.i(
            TAG,
            "hero preview playing live=$isLive startMs=$startPositionMs " +
                "source=${source::class.simpleName}",
        )
    }

    private fun releasePlayerOnly() {
        playerView.player = null
        player?.release()
        player = null
    }

    companion object {
        private const val TAG = "FireplaceHero"
        private const val DEBOUNCE_MS = 350L
        private const val PREVIEW_MAX_W = 1280
        private const val PREVIEW_MAX_H = 720
    }
}
