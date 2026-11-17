package com.fireplace.tv.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

@UnstableApi
object FireplacePlayerFactory {

    private const val TAG = "FireplaceCodec"
    private const val MAX_WIDTH = 3840
    private const val MAX_HEIGHT = 2160

    fun create(
        context: Context,
        vp9Policy: Vp9Policy,
        loadControl: DefaultLoadControl,
    ): Pair<ExoPlayer, DefaultTrackSelector> {
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(MAX_WIDTH, MAX_HEIGHT)
                // Start at best ladder step immediately (avoid low→4K ABR ramp).
                .setForceHighestSupportedBitrate(true)
                // Prefer AVC, then HEVC; VP9 last (often filtered by codec selector).
                .setPreferredVideoMimeTypes(
                    MimeTypes.VIDEO_H264,
                    MimeTypes.VIDEO_H265,
                    MimeTypes.VIDEO_VP9,
                )
                .setExceedRendererCapabilitiesIfNecessary(false)
                .setViewportSize(MAX_WIDTH, MAX_HEIGHT, /* orientationMayChange= */ true)
                .setConstrainAudioChannelCountToDeviceCapabilities(false)
                .build()
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(FireplaceMediaCodecSelector { vp9Policy })

        Log.i(
            TAG,
            "build player vp9Policy=$vp9Policy prefer=AVC,HEVC forceHighestBitrate=true max=${MAX_WIDTH}x$MAX_HEIGHT",
        )

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()

        return player to trackSelector
    }
}
