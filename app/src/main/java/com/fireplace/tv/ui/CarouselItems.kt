package com.fireplace.tv.ui

import com.fireplace.tv.data.FireplaceVideo

sealed class CarouselItem {
    data object EdgeSpacer : CarouselItem()
    data class Video(val video: FireplaceVideo) : CarouselItem()
}
