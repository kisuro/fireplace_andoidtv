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
 *
 * Live: HLS, then DASH only — never merged progressive (brittle / expires fast on TV).
 * VOD: HLS/DASH preferred for Android TV stability, then muxed progressive, then merged A/V.
 * Quality: best available up to 4K when progressive/merged is used.
 */
class YoutubeStreamResolver {

    fun resolve(watchUrl: String, expectLive: Boolean = false): ResolvedStream {
        Log.i(TAG, "resolve start url=$watchUrl expectLive=$expectLive")
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "extractor failed type=${e.javaClass.simpleName} msg=${e.message}",
                e,
            )
            throw e
        }

        val extractorLive = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.POST_LIVE_STREAM
        // Catalog/expectLive wins: NewPipe sometimes labels live fireplace HLS as non-LIVE.
        val isLive = expectLive || extractorLive

        val hls = info.hlsUrl.takeIf { it.isNotBlank() }
        val dash = info.dashMpdUrl.takeIf { it.isNotBlank() }
        Log.i(
            TAG,
            "meta id=${info.id} streamType=${info.streamType} expectLive=$expectLive " +
                "extractorLive=$extractorLive effectiveLive=$isLive " +
                "hls=${hls != null} dash=${dash != null} " +
                "v=${info.videoStreams.size} vo=${info.videoOnlyStreams.size} a=${info.audioStreams.size}",
        )

        val playback = selectPlayback(info, isLive, hls, dash)
        val height = when (playback) {
            is PlaybackSource.Merged -> estimateHeightFromUrl(playback.videoUrl)
            is PlaybackSource.Progressive -> estimateHeightFromUrl(playback.url)
            else -> null
        }

        Log.i(
            TAG,
            "Resolved ${info.id}: live=$isLive (expect=$expectLive extractor=$extractorLive) " +
                "source=${playback::class.simpleName} height=$height",
        )

        return ResolvedStream(
            youtubeId = info.id,
            isLive = isLive,
            playback = playback,
            title = info.name,
            selectedHeight = height,
        )
    }

    private fun selectPlayback(
        info: StreamInfo,
        isLive: Boolean,
        hls: String?,
        dash: String?,
    ): PlaybackSource {
        // Live must stay on adaptive manifests — progressive/merged die quickly.
        if (isLive) {
            if (hls != null) return PlaybackSource.Hls(hls)
            if (dash != null) return PlaybackSource.Dash(dash)
            throw IllegalStateException(
                "No HLS/DASH for live ${info.id} (type=${info.streamType})",
            )
        }

        // VOD: adaptive first (stable on Android TV), then muxed progressive, then merge.
        if (hls != null) return PlaybackSource.Hls(hls)
        if (dash != null) return PlaybackSource.Dash(dash)

        val progressive = bestProgressive(info.videoStreams)
        if (progressive != null) {
            return PlaybackSource.Progressive(progressive.content)
        }

        val videoOnly = bestVideoOnly(info.videoOnlyStreams)
        val audio = bestAudio(info.audioStreams)
        if (videoOnly != null && audio != null) {
            Log.w(TAG, "Falling back to merged A/V for ${info.id}")
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
                compareByDescending<VideoStream> { codecPreference(it) }
                    .thenByDescending { it.height }
                    .thenByDescending { it.bitrate },
            )
            .firstOrNull()
    }

    private fun bestVideoOnly(streams: List<VideoStream>): VideoStream? {
        val ranked = compareByDescending<VideoStream> { codecPreference(it) }
            .thenByDescending { it.height }
            .thenByDescending { it.bitrate }
        return streams
            .asSequence()
            .filter { it.content.isNotBlank() }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.height in 1..MAX_HEIGHT }
            .sortedWith(ranked)
            .firstOrNull()
            ?: streams
                .asSequence()
                .filter { it.content.isNotBlank() }
                .filter { it.height in 1..MAX_HEIGHT }
                .sortedWith(ranked)
                .firstOrNull()
    }

    /** Prefer AVC, then HEVC, demote VP9/AV1 for MediaTek TV stability. */
    private fun codecPreference(stream: VideoStream): Int {
        val hay = buildString {
            append(stream.codec.orEmpty())
            append(' ')
            append(stream.format?.toString().orEmpty())
            append(' ')
            append(stream.content)
        }.lowercase()
        return when {
            "avc" in hay || "h264" in hay || "avc1" in hay -> 3
            "hev" in hay || "hvc1" in hay || "h265" in hay || "hevc" in hay -> 2
            "vp9" in hay || "vp09" in hay -> 0
            "av01" in hay || "av1" in hay -> 0
            else -> 1
        }
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
        private const val TAG = "FireplaceExtract"
        private const val MAX_HEIGHT = 2160
        private val HEIGHT_IN_URL = Regex("""[/&?](?:itag=|quality_label=)?(?:(\d{3,4})p)""")
    }
}
