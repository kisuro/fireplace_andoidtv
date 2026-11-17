package com.fireplace.tv.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Resolution-first video picker for Android TV / MediaTek.
 *
 * Prefer max height ≤4K, then codec: HEVC → AVC → VP9 (VP9 only if allowed).
 * Never let 1080p AVC beat 2160p HEVC via mime preference.
 */
@UnstableApi
object FireplaceQualityPicker {

    private const val TAG = "FireplaceCodec"
    const val MAX_HEIGHT = 2160
    const val MAX_WIDTH = 3840

    data class Candidate(
        val groupIndex: Int,
        val trackIndex: Int,
        val group: TrackGroup,
        val format: Format,
    ) {
        val key: String
            get() = "${format.sampleMimeType}|${format.height}|${format.width}|${format.bitrate}"
    }

    fun listCandidates(tracks: Tracks, allowVp9: Boolean): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (gi in 0 until tracks.groups.size) {
            val group = tracks.groups[gi]
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val format = group.getTrackFormat(ti)
                val height = format.height
                if (height <= 0 || height > MAX_HEIGHT) continue
                if (format.width > MAX_WIDTH && format.width > 0) continue
                val mime = format.sampleMimeType.orEmpty()
                if (!allowVp9 && isVp9(mime, format.codecs)) continue
                out += Candidate(gi, ti, group.mediaTrackGroup, format)
            }
        }
        return out.sortedWith(qualityComparator())
    }

    fun pickBest(
        tracks: Tracks,
        allowVp9: Boolean,
        excludedKeys: Set<String>,
    ): Candidate? {
        val ranked = listCandidates(tracks, allowVp9).filterNot { it.key in excludedKeys }
        val best = ranked.firstOrNull()
        if (best != null) {
            Log.i(
                TAG,
                "pickBest mime=${best.format.sampleMimeType} codecs=${best.format.codecs} " +
                    "size=${best.format.width}x${best.format.height} " +
                    "fps=${best.format.frameRate} bitrate=${best.format.bitrate} " +
                    "allowVp9=$allowVp9 excluded=${excludedKeys.size} alternatives=${ranked.size}",
            )
            ranked.take(5).forEachIndexed { i, c ->
                Log.i(
                    TAG,
                    "  alt[$i] ${c.format.width}x${c.format.height} " +
                        "${c.format.sampleMimeType} br=${c.format.bitrate}",
                )
            }
        } else {
            Log.w(TAG, "pickBest: no candidates allowVp9=$allowVp9 excluded=${excludedKeys.size}")
        }
        return best
    }

    fun applyOverride(selector: DefaultTrackSelector, candidate: Candidate) {
        val override = TrackSelectionOverride(candidate.group, listOf(candidate.trackIndex))
        selector.parameters = selector.buildUponParameters()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setOverrideForType(override)
            .setMaxVideoSize(MAX_WIDTH, MAX_HEIGHT)
            .setForceHighestSupportedBitrate(true)
            .build()
        Log.i(
            TAG,
            "override applied ${candidate.format.width}x${candidate.format.height} " +
                "${candidate.format.sampleMimeType}",
        )
    }

    fun codecRank(mime: String?, codecs: String?): Int {
        val hay = "${mime.orEmpty()} ${codecs.orEmpty()}".lowercase()
        return when {
            MimeTypes.VIDEO_H265.equals(mime, true) ||
                "hev1" in hay || "hvc1" in hay || "hevc" in hay || "h265" in hay -> 3
            MimeTypes.VIDEO_H264.equals(mime, true) ||
                "avc" in hay || "avc1" in hay || "h264" in hay -> 2
            isVp9(mime, codecs) -> 1
            else -> 0
        }
    }

    fun isVp9(mime: String?, codecs: String?): Boolean {
        val hay = "${mime.orEmpty()} ${codecs.orEmpty()}".lowercase()
        return MimeTypes.VIDEO_VP9.equals(mime, true) || "vp9" in hay || "vp09" in hay
    }

    private fun qualityComparator(): Comparator<Candidate> {
        return compareByDescending<Candidate> { it.format.height.coerceAtMost(MAX_HEIGHT) }
            .thenByDescending { codecRank(it.format.sampleMimeType, it.format.codecs) }
            .thenByDescending { it.format.bitrate }
            .thenByDescending { it.format.width }
    }
}
