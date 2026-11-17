package com.kisuro.fireplace.tv.ui

import com.kisuro.fireplace.tv.data.FireplaceVideo

sealed class CarouselItem {
    data object EdgeSpacer : CarouselItem()
    data class Video(val video: FireplaceVideo) : CarouselItem()
}
