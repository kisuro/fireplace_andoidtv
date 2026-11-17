package com.fireplace.tv.extract

import android.util.Log
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Resolves YouTube watch/live URLs into ExoPlayer-ready media URLs via NewPipe Extractor.
 * Prefers HLS/DASH for reliability (especially live), then progressive, then merged A/V.
 * Picks the best quality up to 4K (2160p).
 */
class YoutubeStreamResolver {

    fun resolve(watchUrl: String): ResolvedStream {
        val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
        val isLive = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.POST_LIVE_STREAM

        val playback = selectPlayback(info, isLive)
        val height = when (playback) {
            is PlaybackSource.Merged -> estimateHeightFromUrl(playback.videoUrl)
            is PlaybackSource.Progressive -> estimateHeightFromUrl(playback.url)
            else -> null
        }

        Log.i(
            TAG,
            "Resolved ${info.id}: live=$isLive source=${playback::class.simpleName} height=$height",
        )

        return ResolvedStream(
            youtubeId = info.id,
            isLive = isLive,
            playback = playback,
            title = info.name,
            selectedHeight = height,
        )
    }

    private fun selectPlayback(info: StreamInfo, isLive: Boolean): PlaybackSource {
        val hls = info.hlsUrl.takeIf { it.isNotBlank() }
        val dash = info.dashMpdUrl.takeIf { it.isNotBlank() }

        // Live: HLS first (continuous refresh), then DASH.
        if (isLive) {
            if (hls != null) return PlaybackSource.Hls(hls)
            if (dash != null) return PlaybackSource.Dash(dash)
        }

        // VOD: prefer progressive muxed when a high-enough quality exists,
        // otherwise HLS/DASH, otherwise merge best video-only + audio.
        val progressive = bestProgressive(info.videoStreams)
        if (progressive != null && (progressive.height >= 720 || (hls == null && dash == null))) {
            return PlaybackSource.Progressive(progressive.content)
        }

        if (hls != null) return PlaybackSource.Hls(hls)
        if (dash != null) return PlaybackSource.Dash(dash)

        if (progressive != null) {
            return PlaybackSource.Progressive(progressive.content)
        }

        val videoOnly = bestVideoOnly(info.videoOnlyStreams)
        val audio = bestAudio(info.audioStreams)
        if (videoOnly != null && audio != null) {
            return PlaybackSource.Merged(videoOnly.content, audio.content)
        }

        throw IllegalStateException("No playable streams found for ${info.id}")
    }

    private fun bestProgressive(streams: List<VideoStream>): VideoStream? {
        return streams
            .asSequence()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { !it.isVideoOnly() }
            .filter { it.content.isNotBlank() }
            .filter { it.height in 1..MAX_HEIGHT }
            .sortedWith(
                compareByDescending<VideoStream> { it.height }
                    .thenByDescending { it.bitrate }
            )
            .firstOrNull()
    }

    private fun bestVideoOnly(streams: List<VideoStream>): VideoStream? {
        return streams
            .asSequence()
            .filter { it.content.isNotBlank() }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.height in 1..MAX_HEIGHT }
            .sortedWith(
                compareByDescending<VideoStream> { it.height }
                    .thenByDescending { it.bitrate }
            )
            .firstOrNull()
            ?: streams
                .asSequence()
                .filter { it.content.isNotBlank() }
                .filter { it.height in 1..MAX_HEIGHT }
                .sortedByDescending { it.height }
                .firstOrNull()
    }

    private fun bestAudio(streams: List<AudioStream>): AudioStream? {
        return streams
            .asSequence()
            .filter { it.content.isNotBlank() }
            .filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP ||
                    it.deliveryMethod == DeliveryMethod.DASH
            }
            .sortedByDescending { it.averageBitrate }
            .firstOrNull()
            ?: streams.firstOrNull { it.content.isNotBlank() }
    }

    private fun estimateHeightFromUrl(url: String): Int? {
        val match = HEIGHT_IN_URL.find(url) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    companion object {
        private const val TAG = "YoutubeStreamResolver"
        private const val MAX_HEIGHT = 2160
        private val HEIGHT_IN_URL = Regex("""[/&?](?:itag=|quality_label=)?(?:(\d{3,4})p)""")
    }
}
