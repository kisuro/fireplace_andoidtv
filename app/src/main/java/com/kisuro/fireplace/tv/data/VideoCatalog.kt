package com.kisuro.fireplace.tv.data

import com.kisuro.fireplace.tv.R

/**
 * Static fireplace catalog.
 *
 * When adding a video: extract a clean mid-video frame (no titles/watermarks),
 * drop it in `res/drawable-nodpi/thumb_fireplace_<id>.webp`, and pass that
 * drawable as [FireplaceVideo.thumbnailResId]. Do not wire YouTube thumb URLs.
 */
object VideoCatalog {
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
    )

    fun findById(id: String): FireplaceVideo? = videos.firstOrNull { it.id == id }
}
