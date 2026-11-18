package com.kisuro.fireplace.tv.data

import androidx.annotation.DrawableRes

/**
 * One carousel fireplace entry.
 *
 * **Thumbnail rule:** every video MUST ship a bundled clean mid-video still
 * ([thumbnailResId] → `res/drawable-nodpi/thumb_fireplace_*.webp`). Never use
 * YouTube preview thumbnails (hqdefault / maxresdefault) — those often carry
 * titles, watermarks, and channel graphics. Remote YT image URLs are not part
 * of the card UI.
 */
data class FireplaceVideo(
    val id: String,
    val title: String,
    val youtubeId: String,
    val isLive: Boolean,
    /** Required clean mid-video frame drawable (no text/overlays). */
    @DrawableRes val thumbnailResId: Int,
) {
    val watchUrl: String
        get() = "https://www.youtube.com/watch?v=$youtubeId"
}
