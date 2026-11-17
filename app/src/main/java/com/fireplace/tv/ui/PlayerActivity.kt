package com.fireplace.tv.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.fireplace.tv.R
import com.fireplace.tv.data.FireplaceVideo
import com.fireplace.tv.data.VideoCatalog
import com.fireplace.tv.extract.OkHttpDownloader
import com.fireplace.tv.extract.PlaybackSource
import com.fireplace.tv.extract.ResolvedStream
import com.fireplace.tv.extract.YoutubeStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var statusOverlay: View
    private lateinit var statusProgress: ProgressBar
    private lateinit var statusMessage: TextView
    private lateinit var statusHint: TextView

    private var player: ExoPlayer? = null
    private var video: FireplaceVideo? = null
    private var resolveJob: Job? = null
    private var liveRefreshJob: Job? = null
    private var recoveryJob: Job? = null
    private var isLivePlayback: Boolean = false
    private var consecutiveFailures: Int = 0
    private var playbackGeneration: Int = 0

    private val resolver = YoutubeStreamResolver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        statusOverlay = findViewById(R.id.status_overlay)
        statusProgress = findViewById(R.id.status_progress)
        statusMessage = findViewById(R.id.status_message)
        statusHint = findViewById(R.id.status_hint)

        playerView.useController = false
        playerView.setControllerAutoShow(false)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        video = videoId?.let { VideoCatalog.findById(it) }
        if (video == null) {
            showError(getString(R.string.error_generic), allowAutoRecover = false)
            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    releasePlayer()
                    finish()
                }
            },
        )

        Log.i(TAG, "open videoId=${video?.id} yt=${video?.youtubeId} live=${video?.isLive}")
        startPlayback(reason = "launch")
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onStart() {
        super.onStart()
        if (video != null && player == null) {
            startPlayback(reason = "restart")
        }
    }

    private fun startPlayback(reason: String) {
        val current = video ?: return
        resolveJob?.cancel()
        recoveryJob?.cancel()
        showLoading(getString(R.string.loading_stream))
        val generation = ++playbackGeneration

        resolveJob = lifecycleScope.launch {
            val maxAttempts = if (current.isLive) MAX_LIVE_RESOLVE_ATTEMPTS else MAX_VOD_RESOLVE_ATTEMPTS
            var lastError: Exception? = null

            for (attempt in 1..maxAttempts) {
                if (!isActive || generation != playbackGeneration) return@launch
                try {
                    if (attempt > 1) {
                        val backoff = backoffMs(attempt - 1)
                        Log.i(TAG, "retry resolve attempt=$attempt/$maxAttempts backoffMs=$backoff reason=$reason")
                        showLoading(getString(R.string.retrying_stream))
                        delay(backoff)
                    }

                    val resolved = withContext(Dispatchers.IO) {
                        resolver.resolve(current.watchUrl, expectLive = current.isLive)
                    }
                    if (!isActive || generation != playbackGeneration) return@launch

                    isLivePlayback = resolved.isLive || current.isLive
                    Log.i(
                        TAG,
                        "bind ok attempt=$attempt live=$isLivePlayback " +
                            "source=${resolved.playback::class.simpleName} yt=${resolved.youtubeId}",
                    )
                    bindPlayer(resolved, generation)
                    consecutiveFailures = 0
                    if (isLivePlayback) {
                        scheduleLiveRefresh(generation)
                    } else {
                        liveRefreshJob?.cancel()
                    }
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                    consecutiveFailures++
                    Log.e(
                        TAG,
                        "resolve fail attempt=$attempt/$maxAttempts " +
                            "type=${e.javaClass.simpleName} cause=${e.cause?.javaClass?.simpleName} " +
                            "msg=${e.message}",
                        e,
                    )
                }
            }

            Log.e(TAG, "resolve exhausted reason=$reason live=${current.isLive}", lastError)
            handleResolveFailure(lastError ?: IllegalStateException("resolve failed"), soft = current.isLive)
        }
    }

    private fun bindPlayer(resolved: ResolvedStream, generation: Int) {
        if (generation != playbackGeneration) return
        releasePlayer(keepJobs = true)
        val dataSourceFactory = buildDataSourceFactory()
        val loadPolicy = DefaultLoadErrorHandlingPolicy(MEDIA_SOURCE_RETRY_COUNT)
        val mediaSource = when (val source = resolved.playback) {
            is PlaybackSource.Hls -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadPolicy)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(source.url))
            }
            is PlaybackSource.Dash -> {
                DashMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadPolicy)
                    .createMediaSource(MediaItem.fromUri(source.url))
            }
            is PlaybackSource.Progressive -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadPolicy)
                    .createMediaSource(MediaItem.fromUri(source.url))
            }
            is PlaybackSource.Merged -> {
                // Avoid for live; VOD last resort only.
                val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadPolicy)
                MergingMediaSource(
                    progressive.createMediaSource(MediaItem.fromUri(source.videoUrl)),
                    progressive.createMediaSource(MediaItem.fromUri(source.audioUrl)),
                )
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 5_000,
                /* maxBufferMs */ 50_000,
                /* bufferForPlaybackMs */ 1_500,
                /* bufferForPlaybackAfterRebufferMs */ 3_000,
            )
            .build()

        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            .also { player = it }
        playerView.player = exo
        exo.addListener(playerListener)
        exo.setMediaSource(mediaSource)
        exo.repeatMode = if (isLivePlayback) {
            Player.REPEAT_MODE_OFF
        } else {
            Player.REPEAT_MODE_ALL
        }
        exo.playWhenReady = true
        exo.prepare()
        // Stay on loading until STATE_READY so a bad URL doesn't flash blank then error.
        showLoading(getString(R.string.loading_stream))
    }

    private fun buildDataSourceFactory(): HttpDataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setUserAgent(OkHttpDownloader.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(25_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com",
                ),
            )
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveFailures = 0
                hideOverlay()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause
            Log.e(
                TAG,
                "player error code=${error.errorCodeName}(${error.errorCode}) " +
                    "cause=${cause?.javaClass?.simpleName} msg=${error.message}",
                error,
            )
            consecutiveFailures++
            // Re-resolve immediately with backoff path — never sit on a dead URL for live.
            startPlayback(reason = "player_error:${error.errorCodeName}")
        }
    }

    private fun scheduleLiveRefresh(generation: Int) {
        liveRefreshJob?.cancel()
        liveRefreshJob = lifecycleScope.launch {
            while (isActive && generation == playbackGeneration) {
                delay(LIVE_REFRESH_INTERVAL_MS)
                if (generation != playbackGeneration) return@launch
                Log.i(TAG, "proactive live URL refresh")
                val current = video ?: return@launch
                try {
                    val resolved = withContext(Dispatchers.IO) {
                        resolver.resolve(current.watchUrl, expectLive = true)
                    }
                    if (!isActive || generation != playbackGeneration) return@launch
                    // Seamless-ish: only rebind if still live adaptive.
                    if (resolved.playback is PlaybackSource.Hls ||
                        resolved.playback is PlaybackSource.Dash
                    ) {
                        bindPlayer(resolved, generation)
                    }
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "live refresh failed type=${e.javaClass.simpleName} msg=${e.message}; keeping stream",
                        e,
                    )
                }
            }
        }
    }

    private fun handleResolveFailure(error: Throwable, soft: Boolean) {
        val text = buildString {
            append(error.javaClass.simpleName)
            append(' ')
            append(error.message.orEmpty())
            append(' ')
            append(error.cause?.javaClass?.simpleName.orEmpty())
            append(' ')
            append(error.cause?.message.orEmpty())
        }.lowercase()

        val message = when {
            "not a bot" in text || "login_required" in text || "signinconfirm" in text ->
                getString(R.string.error_bot_check)
            error is IOException || error.cause is IOException || "http" in text ->
                getString(R.string.error_network)
            else -> getString(R.string.error_extractor)
        }

        Log.e(TAG, "show error soft=$soft msg=$message detail=$text")
        showError(message, allowAutoRecover = soft || isLivePlayback)

        if (soft || isLivePlayback) {
            scheduleSoftRecovery()
        }
    }

    private fun scheduleSoftRecovery() {
        recoveryJob?.cancel()
        recoveryJob = lifecycleScope.launch {
            var attempt = 0
            while (isActive) {
                attempt++
                val wait = min(SOFT_RECOVERY_MAX_MS, backoffMs(attempt) * 2)
                Log.i(TAG, "soft recovery scheduled in ${wait}ms attempt=$attempt")
                delay(wait)
                if (!isActive) return@launch
                // Keep UI in retry mode rather than permanent dead error.
                showLoading(getString(R.string.retrying_stream))
                startPlayback(reason = "soft_recovery_$attempt")
                // startPlayback cancels this job; if it returns to error it will reschedule.
                return@launch
            }
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 5)
        return min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * (1L shl shift))
    }

    private fun showLoading(message: String) {
        statusOverlay.visibility = View.VISIBLE
        statusProgress.visibility = View.VISIBLE
        statusHint.visibility = View.GONE
        statusMessage.text = message
    }

    private fun showError(message: String, allowAutoRecover: Boolean) {
        statusOverlay.visibility = View.VISIBLE
        statusProgress.visibility = if (allowAutoRecover) View.VISIBLE else View.GONE
        statusHint.visibility = View.VISIBLE
        statusMessage.text = if (allowAutoRecover) {
            "$message\n${getString(R.string.retrying_in_background)}"
        } else {
            message
        }
    }

    private fun hideOverlay() {
        statusOverlay.visibility = View.GONE
    }

    private fun releasePlayer(keepJobs: Boolean = false) {
        playerView.player = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        if (!keepJobs) {
            resolveJob?.cancel()
            liveRefreshJob?.cancel()
            recoveryJob?.cancel()
        }
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        private const val TAG = "FireplacePlayer"
        /** YouTube live manifests expire far sooner than hours. */
        private const val LIVE_REFRESH_INTERVAL_MS = 8 * 60 * 1000L
        private const val MAX_LIVE_RESOLVE_ATTEMPTS = 6
        private const val MAX_VOD_RESOLVE_ATTEMPTS = 4
        private const val MEDIA_SOURCE_RETRY_COUNT = 6
        private const val BACKOFF_BASE_MS = 700L
        private const val BACKOFF_MAX_MS = 12_000L
        private const val SOFT_RECOVERY_MAX_MS = 30_000L
    }
}
