package com.kisuro.fireplace.tv.data

object VideoCatalog {
    val videos: List<FireplaceVideo> = listOf(
        FireplaceVideo(
            id = "1",
            title = "Cozy 4K Hearth",
            youtubeId = "cuPPcx9KRVw",
            isLive = false,
        ),
        FireplaceVideo(
            id = "2",
            title = "Evening Calm (Live)",
            youtubeId = "mSX3OyW9Rao",
            isLive = true,
        ),
        FireplaceVideo(
            id = "3",
            title = "Crackling Logs (Live)",
            youtubeId = "g6Ye4xwXyAw",
            isLive = true,
        ),
        FireplaceVideo(
            id = "4",
            title = "Stone Fireplace",
            youtubeId = "2hRjlZ86tpE",
            isLive = false,
        ),
        FireplaceVideo(
            id = "5",
            title = "Warm Embers",
            youtubeId = "gQb5YzLFIno",
            isLive = false,
        ),
        FireplaceVideo(
            id = "6",
            title = "HDR Fireplace",
            youtubeId = "qeWLeTQAjzU",
            isLive = false,
        ),
        FireplaceVideo(
            id = "7",
            title = "Wood Fire Sleep",
            youtubeId = "w3j6dMm1-fU",
            isLive = false,
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
