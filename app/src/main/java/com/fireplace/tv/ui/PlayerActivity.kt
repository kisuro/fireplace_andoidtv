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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
    private var isLivePlayback: Boolean = false
    private var retryCount: Int = 0

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

        // Own UI only: hide all ExoPlayer chrome.
        playerView.useController = false
        playerView.setControllerAutoShow(false)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        video = videoId?.let { VideoCatalog.findById(it) }
        if (video == null) {
            showError(getString(R.string.error_generic))
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

        startPlayback(forceRefresh = true)
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onStart() {
        super.onStart()
        if (video != null && player == null) {
            startPlayback(forceRefresh = true)
        }
    }

    private fun startPlayback(forceRefresh: Boolean) {
        val current = video ?: return
        resolveJob?.cancel()
        showLoading()
        resolveJob = lifecycleScope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) {
                    resolver.resolve(current.watchUrl)
                }
                if (!isActive) return@launch
                isLivePlayback = resolved.isLive || current.isLive
                bindPlayer(resolved)
                retryCount = 0
                if (isLivePlayback) {
                    scheduleLiveRefresh()
                } else {
                    liveRefreshJob?.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Resolve failed (force=$forceRefresh)", e)
                handleResolveFailure(e)
            }
        }
    }

    private fun bindPlayer(resolved: ResolvedStream) {
        releasePlayer(keepJobs = true)
        val dataSourceFactory = buildDataSourceFactory()
        val mediaSource = when (val source = resolved.playback) {
            is PlaybackSource.Hls -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(source.url))
            }
            is PlaybackSource.Dash -> {
                DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(source.url))
            }
            is PlaybackSource.Progressive -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(source.url))
            }
            is PlaybackSource.Merged -> {
                val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)
                MergingMediaSource(
                    progressive.createMediaSource(MediaItem.fromUri(source.videoUrl)),
                    progressive.createMediaSource(MediaItem.fromUri(source.audioUrl)),
                )
            }
        }

        val exo = ExoPlayer.Builder(this).build().also { player = it }
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
        hideOverlay()
    }

    private fun buildDataSourceFactory(): HttpDataSource.Factory {
        return DefaultHttpDataSource.Factory()
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
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                hideOverlay()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName}", error)
            if (isLivePlayback && retryCount < MAX_LIVE_RETRIES) {
                retryCount++
                startPlayback(forceRefresh = true)
                return
            }
            if (!isLivePlayback && retryCount < MAX_VOD_RETRIES) {
                retryCount++
                startPlayback(forceRefresh = true)
                return
            }
            handleResolveFailure(error)
        }
    }

    private fun scheduleLiveRefresh() {
        liveRefreshJob?.cancel()
        liveRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(LIVE_REFRESH_INTERVAL_MS)
                Log.i(TAG, "Refreshing live stream URL")
                try {
                    val current = video ?: return@launch
                    val resolved = withContext(Dispatchers.IO) {
                        resolver.resolve(current.watchUrl)
                    }
                    if (!isActive) return@launch
                    bindPlayer(resolved)
                } catch (e: Exception) {
                    Log.w(TAG, "Live refresh failed; keeping current stream", e)
                }
            }
        }
    }

    private fun handleResolveFailure(error: Throwable) {
        val message = when {
            error is IOException || error.cause is IOException ->
                getString(R.string.error_network)
            else -> getString(R.string.error_extractor)
        }
        showError(message)
    }

    private fun showLoading() {
        statusOverlay.visibility = View.VISIBLE
        statusProgress.visibility = View.VISIBLE
        statusHint.visibility = View.GONE
        statusMessage.setText(R.string.loading_stream)
    }

    private fun showError(message: String) {
        statusOverlay.visibility = View.VISIBLE
        statusProgress.visibility = View.GONE
        statusHint.visibility = View.VISIBLE
        statusMessage.text = message
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
        }
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        private const val TAG = "PlayerActivity"
        private const val LIVE_REFRESH_INTERVAL_MS = 4 * 60 * 60 * 1000L
        private const val MAX_LIVE_RETRIES = 3
        private const val MAX_VOD_RETRIES = 2
    }
}
