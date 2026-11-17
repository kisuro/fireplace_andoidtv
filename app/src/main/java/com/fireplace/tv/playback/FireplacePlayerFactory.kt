package com.fireplace.tv.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

@UnstableApi
object FireplacePlayerFactory {

    private const val TAG = "FireplaceCodec"

    fun create(
        context: Context,
        vp9Policy: Vp9Policy,
        loadControl: DefaultLoadControl,
    ): Pair<ExoPlayer, DefaultTrackSelector> {
        // No AVC-first mime preference — that forced 1080p AVC over 4K HEVC.
        // FireplaceQualityPicker applies an explicit resolution-first override.
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(FireplaceQualityPicker.MAX_WIDTH, FireplaceQualityPicker.MAX_HEIGHT)
                .setForceHighestSupportedBitrate(true)
                .setExceedRendererCapabilitiesIfNecessary(false)
                .setViewportSize(
                    FireplaceQualityPicker.MAX_WIDTH,
                    FireplaceQualityPicker.MAX_HEIGHT,
                    /* orientationMayChange= */ true,
                )
                .setConstrainAudioChannelCountToDeviceCapabilities(false)
                .build()
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(FireplaceMediaCodecSelector { vp9Policy })

        Log.i(
            TAG,
            "build player vp9Policy=$vp9Policy selection=height-first " +
                "codecRank=HEVC>AVC>VP9 max=${FireplaceQualityPicker.MAX_WIDTH}x${FireplaceQualityPicker.MAX_HEIGHT}",
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
