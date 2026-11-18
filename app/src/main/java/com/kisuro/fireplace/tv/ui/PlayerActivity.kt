package com.kisuro.fireplace.tv.ui

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.kisuro.fireplace.tv.R
import com.kisuro.fireplace.tv.data.FireplaceVideo
import com.kisuro.fireplace.tv.data.VideoCatalog
import com.kisuro.fireplace.tv.extract.OkHttpDownloader
import com.kisuro.fireplace.tv.extract.PlaybackSource
import com.kisuro.fireplace.tv.extract.ResolvedStream
import com.kisuro.fireplace.tv.extract.YoutubeStreamResolver
import com.kisuro.fireplace.tv.playback.FireplacePlayerFactory
import com.kisuro.fireplace.tv.playback.FireplaceQualityPicker
import com.kisuro.fireplace.tv.playback.Vp9Policy
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
    private var trackSelector: DefaultTrackSelector? = null
    private var video: FireplaceVideo? = null
    private var lastResolved: ResolvedStream? = null
    private var resolveJob: Job? = null
    private var liveRefreshJob: Job? = null
    private var recoveryJob: Job? = null
    private var isLivePlayback: Boolean = false
    private var consecutiveFailures: Int = 0
    private var playbackGeneration: Int = 0
    private var vp9Policy: Vp9Policy = Vp9Policy.DEMOTE_MTK_VP9
    private val excludedFormatKeys = linkedSetOf<String>()
    private var lastPickedKey: String? = null
    private var qualityAppliedForTracks: Int = -1
    private var lastCatalogSwitchUptimeMs: Long = 0L

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

        Log.i(TAG, "open videoId=${video?.id} yt=${video?.youtubeId} catalogLive=${video?.isLive}")
        startPlayback(reason = "launch")
    }

    /**
     * In fullscreen playback, LEFT/RIGHT swap to the previous/next catalog
     * fireplace without leaving [PlayerActivity]. BACK still finishes to carousel.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val delta = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    switchCatalogVideo(delta)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun switchCatalogVideo(delta: Int) {
        val current = video ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastCatalogSwitchUptimeMs < CATALOG_SWITCH_DEBOUNCE_MS) {
            return
        }
        val next = VideoCatalog.neighbor(current.id, delta) ?: return
        if (next.id == current.id) return
        lastCatalogSwitchUptimeMs = now
        Log.i(
            TAG,
            "catalog switch delta=$delta from=${current.id}(${current.title}) " +
                "to=${next.id}(${next.title})",
        )
        video = next
        // Fresh video → reset codec fallback state for the new source.
        vp9Policy = Vp9Policy.DEMOTE_MTK_VP9
        consecutiveFailures = 0
        lastResolved = null
        startPlayback(reason = if (delta > 0) "next" else "prev")
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
        // Fresh resolve path may reset exclusions unless we're mid decode-fallback.
        if (!reason.startsWith("decode_fail") && !reason.startsWith("player_error")) {
            excludedFormatKeys.clear()
            lastPickedKey = null
        }
        showLoading(getString(R.string.loading_stream))
        val generation = ++playbackGeneration

        resolveJob = lifecycleScope.launch {
            val catalogLive = current.isLive
            val maxAttempts = if (catalogLive) MAX_LIVE_RESOLVE_ATTEMPTS else MAX_VOD_RESOLVE_ATTEMPTS
            var lastError: Exception? = null

            for (attempt in 1..maxAttempts) {
                if (!isActive || generation != playbackGeneration) return@launch
                try {
                    if (attempt > 1) {
                        val backoff = backoffMs(attempt - 1)
                        Log.i(TAG, "retry resolve attempt=$attempt/$maxAttempts backoffMs=$backoff reason=$reason vp9=$vp9Policy")
                        showLoading(getString(R.string.retrying_stream))
                        delay(backoff)
                    }

                    val resolved = withContext(Dispatchers.IO) {
                        // Catalog live always wins — NewPipe sometimes reports VOD-like for live HLS.
                        resolver.resolve(current.watchUrl, expectLive = catalogLive)
                    }
                    if (!isActive || generation != playbackGeneration) return@launch

                    isLivePlayback = catalogLive || resolved.isLive
                    lastResolved = resolved.copy(isLive = isLivePlayback)
                    Log.i(
                        TAG,
                        "bind ok attempt=$attempt catalogLive=$catalogLive resolvedLive=${resolved.isLive} " +
                            "effectiveLive=$isLivePlayback source=${resolved.playback::class.simpleName} " +
                            "yt=${resolved.youtubeId} vp9=$vp9Policy",
                    )
                    bindPlayer(lastResolved!!, generation)
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
            handleResolveFailure(
                lastError ?: IllegalStateException("resolve failed"),
                soft = current.isLive,
            )
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
                val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadPolicy)
                MergingMediaSource(
                    progressive.createMediaSource(MediaItem.fromUri(source.videoUrl)),
                    progressive.createMediaSource(MediaItem.fromUri(source.audioUrl)),
                )
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 50_000, 1_500, 3_000)
            .build()

        val (exo, selector) = FireplacePlayerFactory.create(this, vp9Policy, loadControl)
        player = exo
        trackSelector = selector
        qualityAppliedForTracks = -1
        playerView.player = exo
        exo.addListener(playerListener)
        exo.setMediaSource(mediaSource)
        exo.repeatMode = if (isLivePlayback) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        exo.playWhenReady = true
        exo.prepare()
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
        override fun onTracksChanged(tracks: Tracks) {
            applyBestQualityOverride(tracks, reason = "tracks_changed")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveFailures = 0
                player?.currentTracks?.let { applyBestQualityOverride(it, reason = "ready") }
                logSelectedVideoFormat()
                hideOverlay()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val formatHint = error.message.orEmpty() + " " + (error.cause?.message.orEmpty())
            val isDecodeFail = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
            val isVp9Decode = isDecodeFail && (
                "vp9" in formatHint.lowercase() ||
                    "vp09" in formatHint.lowercase() ||
                    "on2.vp9" in formatHint.lowercase() ||
                    "mtk.vp9" in formatHint.lowercase()
                )

            Log.e(
                TAG,
                "player error code=${error.errorCodeName}(${error.errorCode}) " +
                    "vp9Decode=$isVp9Decode policy=$vp9Policy lastPick=$lastPickedKey " +
                    "cause=${error.cause?.javaClass?.simpleName} msg=${error.message}",
                error,
            )

            if (isDecodeFail) {
                showLoading(getString(R.string.retrying_stream))
                // Blacklist the failed representation; try next (e.g. 4K HEVC → 1440 → 1080).
                lastPickedKey?.let { excludedFormatKeys += it }
                player?.videoFormat?.let { fmt ->
                    excludedFormatKeys +=
                        "${fmt.sampleMimeType}|${fmt.height}|${fmt.width}|${fmt.bitrate}"
                }
                Log.w(TAG, "DECODING_FAILED → exclude=$excludedFormatKeys and pick next")

                if (isVp9Decode && vp9Policy != Vp9Policy.BAN_VP9) {
                    vp9Policy = Vp9Policy.BAN_VP9
                }

                val tracks = player?.currentTracks
                val selector = trackSelector
                if (tracks != null && selector != null) {
                    val allowVp9 = vp9Policy != Vp9Policy.BAN_VP9
                    val next = FireplaceQualityPicker.pickBest(tracks, allowVp9, excludedFormatKeys)
                    if (next != null) {
                        lastPickedKey = next.key
                        FireplaceQualityPicker.applyOverride(selector, next)
                        // Seek/reprepare current item so the new override takes effect.
                        player?.prepare()
                        player?.playWhenReady = true
                        return
                    }
                }

                // Ladder exhausted for this manifest — re-resolve with stricter VP9 policy.
                if (vp9Policy != Vp9Policy.BAN_VP9) {
                    vp9Policy = Vp9Policy.BAN_VP9
                }
                val resolved = lastResolved
                if (resolved != null) {
                    qualityAppliedForTracks = -1
                    bindPlayer(resolved, playbackGeneration)
                } else {
                    startPlayback(reason = "decode_fail_exhausted")
                }
                return
            }

            consecutiveFailures++
            startPlayback(reason = "player_error:${error.errorCodeName}")
        }
    }

    private fun applyBestQualityOverride(tracks: Tracks, reason: String) {
        val selector = trackSelector ?: return
        if (!tracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)) return
        // Avoid fighting ourselves every frame; re-apply when tracks identity changes.
        val fingerprint = tracks.groups.sumOf { it.length } * 31 + tracks.groups.size
        if (fingerprint == qualityAppliedForTracks && lastPickedKey != null) return

        val allowVp9 = vp9Policy != Vp9Policy.BAN_VP9
        val best = FireplaceQualityPicker.pickBest(tracks, allowVp9, excludedFormatKeys) ?: return
        lastPickedKey = best.key
        qualityAppliedForTracks = fingerprint
        FireplaceQualityPicker.applyOverride(selector, best)
        Log.i(
            TAG,
            "quality[$reason] selected ${best.format.width}x${best.format.height} " +
                "mime=${best.format.sampleMimeType} codecs=${best.format.codecs} " +
                "bitrate=${best.format.bitrate}",
        )
    }

    private fun logSelectedVideoFormat() {
        val exo = player ?: return
        val format = exo.videoFormat
        Log.i(
            TAG,
            "playing mime=${format?.sampleMimeType} codecs=${format?.codecs} " +
                "size=${format?.width}x${format?.height} fps=${format?.frameRate} " +
                "bitrate=${format?.bitrate} vp9Policy=$vp9Policy excluded=${excludedFormatKeys.size}",
        )
        if (format != null && format.height in 1 until 2160) {
            Log.w(
                TAG,
                "below 4K now (${format.width}x${format.height}); " +
                    "higher tracks missing or excluded=$excludedFormatKeys",
            )
        }
        if (format?.sampleMimeType == MimeTypes.VIDEO_VP9) {
            Log.w(TAG, "VP9 selected under policy=$vp9Policy (non-MTK decoder path)")
        }
    }

    private fun scheduleLiveRefresh(generation: Int) {
        liveRefreshJob?.cancel()
        liveRefreshJob = lifecycleScope.launch {
            while (isActive && generation == playbackGeneration) {
                delay(LIVE_REFRESH_INTERVAL_MS)
                if (generation != playbackGeneration) return@launch
                // Catalog live always refreshes even if extractor said live=false.
                val current = video ?: return@launch
                if (!current.isLive && !isLivePlayback) {
                    Log.i(TAG, "skip live refresh (not live)")
                    return@launch
                }
                Log.i(TAG, "proactive live URL refresh catalogLive=${current.isLive}")
                try {
                    val resolved = withContext(Dispatchers.IO) {
                        resolver.resolve(current.watchUrl, expectLive = true)
                    }
                    if (!isActive || generation != playbackGeneration) return@launch
                    val effective = resolved.copy(isLive = true)
                    lastResolved = effective
                    if (effective.playback is PlaybackSource.Hls ||
                        effective.playback is PlaybackSource.Dash
                    ) {
                        bindPlayer(effective, generation)
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
        showError(message, allowAutoRecover = soft || isLivePlayback || video?.isLive == true)

        if (soft || isLivePlayback || video?.isLive == true) {
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
                showLoading(getString(R.string.retrying_stream))
                // Soft recovery also tries AVC-only.
                if (vp9Policy != Vp9Policy.BAN_VP9) {
                    vp9Policy = Vp9Policy.BAN_VP9
                }
                startPlayback(reason = "soft_recovery_$attempt")
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
        trackSelector = null
        qualityAppliedForTracks = -1
        if (!keepJobs) {
            resolveJob?.cancel()
            liveRefreshJob?.cancel()
            recoveryJob?.cancel()
            // Keep excludedFormatKeys across soft rebinds within the activity so we
            // don't immediately re-select a known-bad 4K track after DECODING_FAILED.
        }
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        private const val TAG = "FireplacePlayer"
        private const val LIVE_REFRESH_INTERVAL_MS = 8 * 60 * 1000L
        private const val MAX_LIVE_RESOLVE_ATTEMPTS = 6
        private const val MAX_VOD_RESOLVE_ATTEMPTS = 4
        private const val MEDIA_SOURCE_RETRY_COUNT = 6
        private const val BACKOFF_BASE_MS = 700L
        private const val BACKOFF_MAX_MS = 12_000L
        private const val SOFT_RECOVERY_MAX_MS = 30_000L
        /** Calm pace when holding LEFT/RIGHT on the remote. */
        private const val CATALOG_SWITCH_DEBOUNCE_MS = 450L
    }
}
