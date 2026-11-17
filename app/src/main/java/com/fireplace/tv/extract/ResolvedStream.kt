package com.fireplace.tv.extract

data class ResolvedStream(
    val youtubeId: String,
    val isLive: Boolean,
    val playback: PlaybackSource,
    val title: String? = null,
    val selectedHeight: Int? = null,
)

sealed class PlaybackSource {
    data class Hls(val url: String) : PlaybackSource()
    data class Dash(val url: String) : PlaybackSource()
    data class Progressive(val url: String) : PlaybackSource()
    data class Merged(
        val videoUrl: String,
        val audioUrl: String,
    ) : PlaybackSource()
}
