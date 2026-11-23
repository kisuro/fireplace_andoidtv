package com.kisuro.fireplace.tv.extract

import android.util.Log
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

/**
 * Resolves YouTube watch/live URLs into ExoPlayer-ready media URLs via NewPipe Extractor.
 *
 * Live: HLS, then DASH only — never merged progressive (brittle / expires fast on TV).
 * VOD: HLS/DASH preferred for Android TV stability, then muxed progressive, then merged A/V.
 * Quality: best available up to 4K when progressive/merged is used.
 */
class YoutubeStreamResolver {

    /**
     * Lightweight catalog gate: true when NewPipe can see a playable stream.
     * Returns false for private/deleted/region blocks and hard extract failures.
     * Transient network / bot-check errors return true so we do not hide tiles on flaky Wi‑Fi.
     */
    fun probeAvailable(watchUrl: String, expectLive: Boolean = false): Boolean {
        return try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
            val hls = info.hlsUrl.takeIf { it.isNotBlank() }
            val dash = info.dashMpdUrl.takeIf { it.isNotBlank() }
            if (hls != null || dash != null) {
                Log.i(TAG, "probe ok adaptive id=${info.id}")
                return true
            }
            if (expectLive) {
                Log.w(TAG, "probe live without HLS/DASH id=${info.id}")
                return false
            }
            val hasProgressive = info.videoStreams.any { it.content.isNotBlank() } ||
                (info.videoOnlyStreams.any { it.content.isNotBlank() } &&
                    info.audioStreams.any { it.content.isNotBlank() })
            Log.i(TAG, "probe ok progressive=$hasProgressive id=${info.id}")
            hasProgressive
        } catch (e: ContentNotAvailableException) {
            Log.w(TAG, "probe unavailable (content): ${e.message}")
            false
        } catch (e: PrivateContentException) {
            Log.w(TAG, "probe unavailable (private): ${e.message}")
            false
        } catch (e: GeographicRestrictionException) {
            Log.w(TAG, "probe unavailable (geo): ${e.message}")
            false
        } catch (e: AgeRestrictedContentException) {
            Log.w(TAG, "probe unavailable (age): ${e.message}")
            false
        } catch (e: PaidContentException) {
            Log.w(TAG, "probe unavailable (paid): ${e.message}")
            false
        } catch (e: ReCaptchaException) {
            Log.w(TAG, "probe transient keep visible (recaptcha): ${e.message}")
            true
        } catch (e: SignInConfirmNotBotException) {
            Log.w(TAG, "probe transient keep visible (bot): ${e.message}")
            true
        } catch (e: Exception) {
            if (isTransientProbeError(e)) {
                Log.w(TAG, "probe transient keep visible: ${e.javaClass.simpleName} ${e.message}")
                true
            } else {
                Log.w(TAG, "probe hide (extract): ${e.javaClass.simpleName} ${e.message}")
                false
            }
        }
    }

    private fun isTransientProbeError(error: Throwable): Boolean {
        val text = buildString {
            append(error.javaClass.simpleName)
            append(' ')
            append(error.message.orEmpty())
            append(' ')
            append(error.cause?.javaClass?.simpleName.orEmpty())
            append(' ')
            append(error.cause?.message.orEmpty())
        }.lowercase()
        return error is IOException ||
            error.cause is IOException ||
            "recaptcha" in text ||
            "not a bot" in text ||
            "login_required" in text ||
            "signinconfirm" in text ||
            "429" in text ||
            "timeout" in text ||
            "timed out" in text
    }

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
