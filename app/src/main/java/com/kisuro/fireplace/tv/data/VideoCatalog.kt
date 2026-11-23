package com.kisuro.fireplace.tv.data

import com.kisuro.fireplace.tv.R

/**
 * Static fireplace catalog.
 *
 * When adding a video: extract a clean mid-video frame (no titles/watermarks),
 * drop it in `res/drawable-nodpi/thumb_fireplace_<id>.webp`, and pass that
 * drawable as [FireplaceVideo.thumbnailResId]. Do not wire YouTube thumb URLs.
 *
 * Start-offset rule (skip intro text): if the source URL `t=` is ≥ 120s use that
 * exact value; if `t=` is missing or &lt; 120s use 120s. Same YouTube id with a
 * different offset is a separate catalog row.
 */
object VideoCatalog {
    /** Default VOD start when URL has no usable `t=` (≥ 2 minutes). */
    const val DEFAULT_START_MS: Long = 120_000L

    val videos: List<FireplaceVideo> = listOf(
        FireplaceVideo(
            id = "1",
            title = "Cozy 4K Hearth",
            youtubeId = "cuPPcx9KRVw",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_1,
        ),
        FireplaceVideo(
            id = "2",
            title = "Evening Calm (Live)",
            youtubeId = "mSX3OyW9Rao",
            isLive = true,
            thumbnailResId = R.drawable.thumb_fireplace_2,
        ),
        FireplaceVideo(
            id = "3",
            title = "Crackling Logs (Live)",
            youtubeId = "g6Ye4xwXyAw",
            isLive = true,
            thumbnailResId = R.drawable.thumb_fireplace_3,
        ),
        FireplaceVideo(
            id = "4",
            title = "Stone Fireplace",
            youtubeId = "2hRjlZ86tpE",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_4,
        ),
        FireplaceVideo(
            id = "5",
            title = "Warm Embers",
            youtubeId = "gQb5YzLFIno",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_5,
        ),
        FireplaceVideo(
            id = "6",
            title = "HDR Fireplace",
            youtubeId = "qeWLeTQAjzU",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_6,
        ),
        FireplaceVideo(
            id = "7",
            title = "Wood Fire Sleep",
            youtubeId = "w3j6dMm1-fU",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_7,
        ),
        FireplaceVideo(
            id = "8",
            title = "Night Cozy Hearth",
            youtubeId = "k89ksrBBdX8",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_8,
        ),
        FireplaceVideo(
            id = "9",
            title = "Modern Stone Flames",
            youtubeId = "AXJGm3DOdIs",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_9,
        ),
        FireplaceVideo(
            id = "10",
            title = "Lake Bonfire",
            youtubeId = "4ApMS8qYWo0",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_10,
        ),
        FireplaceVideo(
            id = "11",
            title = "Rustic Crackling",
            youtubeId = "vU2zX8ICyDg",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_11,
        ),
        FireplaceVideo(
            id = "12",
            title = "Christmas Hearth",
            youtubeId = "QL3KtoxXUco",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_12,
        ),
        // --- Twelve new VODs (ids 13–24) ---
        FireplaceVideo(
            id = "13",
            title = "Realtime Brick Fire",
            youtubeId = "1ieNfIk3Ruo",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_13,
            startPositionMs = 2_103_000L, // t=2103s
        ),
        FireplaceVideo(
            id = "14",
            title = "Stone Hearth Embers",
            youtubeId = "4YCFhFKRZsc",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_14,
            startPositionMs = 4_836_000L, // t=4836s
        ),
        FireplaceVideo(
            id = "15",
            title = "Modern Glass Fireplace",
            youtubeId = "Bsa7VDQZrGM",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_15,
            startPositionMs = DEFAULT_START_MS, // t=10s → 120s
        ),
        FireplaceVideo(
            id = "16",
            title = "Mesmerizing Slow Flames",
            youtubeId = "G9AAllIv7UM",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_16,
            startPositionMs = DEFAULT_START_MS,
        ),
        FireplaceVideo(
            id = "17",
            title = "Quiet Grate Embers",
            youtubeId = "W1NI3lfdMug",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_17,
            startPositionMs = DEFAULT_START_MS, // t=13s → 120s
        ),
        FireplaceVideo(
            id = "18",
            title = "Foggy Forest Campfire",
            youtubeId = "8KrLtLr-Gy8",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_18,
            startPositionMs = DEFAULT_START_MS,
        ),
        FireplaceVideo(
            id = "19",
            title = "Brick Arch Fireplace",
            youtubeId = "UgHKb_7884o",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_19,
            startPositionMs = DEFAULT_START_MS,
        ),
        FireplaceVideo(
            id = "20",
            title = "Realtime Brick Embers",
            youtubeId = "1ieNfIk3Ruo",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_20,
            startPositionMs = 2_366_000L, // t=2366s (same YT id as 13)
        ),
        FireplaceVideo(
            id = "21",
            title = "Stone Arch Crackling",
            youtubeId = "Lk5bEoyAsHg",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_21,
            startPositionMs = DEFAULT_START_MS,
        ),
        FireplaceVideo(
            id = "22",
            title = "Yule Andiron Fire",
            youtubeId = "iaQBQp5tgcw",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_22,
            startPositionMs = DEFAULT_START_MS,
        ),
        FireplaceVideo(
            id = "23",
            title = "Mountain Lake Campfire",
            youtubeId = "WiJvnbsA2CI",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_23,
            startPositionMs = DEFAULT_START_MS,
        ),
        FireplaceVideo(
            id = "24",
            title = "Cozy Brick Fireplace",
            youtubeId = "nrM5Ynk-hIU",
            isLive = false,
            thumbnailResId = R.drawable.thumb_fireplace_24,
            startPositionMs = DEFAULT_START_MS,
        ),
    )

    fun findById(id: String): FireplaceVideo? = videos.firstOrNull { it.id == id }

    /** Previous/next in carousel order; wraps at both ends. */
    fun neighbor(currentId: String, delta: Int): FireplaceVideo? {
        if (videos.isEmpty()) return null
        val index = videos.indexOfFirst { it.id == currentId }
        if (index < 0) return null
        return videos[(index + delta).mod(videos.size)]
    }
}
