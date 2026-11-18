package com.kisuro.fireplace.tv.data

import androidx.annotation.DrawableRes

data class FireplaceVideo(
    val id: String,
    val title: String,
    val youtubeId: String,
    val isLive: Boolean,
    /** Bundled clean mid-video frame; preferred over remote YouTube thumbs. */
    @DrawableRes val thumbnailResId: Int? = null,
) {
    val watchUrl: String
        get() = "https://www.youtube.com/watch?v=$youtubeId"

    val thumbnailUrl: String
        get() = "https://i.ytimg.com/vi/$youtubeId/hqdefault.jpg"

    val maxThumbnailUrl: String
        get() = "https://i.ytimg.com/vi/$youtubeId/maxresdefault.jpg"
}
