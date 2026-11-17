package com.fireplace.tv.ui

import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import com.fireplace.tv.data.FireplaceVideo

class CarouselPresenterSelector(
    private val videoPresenter: VideoCardPresenter = VideoCardPresenter(),
    private val spacerPresenter: EdgeSpacerPresenter = EdgeSpacerPresenter(),
) : PresenterSelector() {

    override fun getPresenter(item: Any?): Presenter {
        return when (item) {
            is CarouselItem.EdgeSpacer -> spacerPresenter
            is CarouselItem.Video -> videoPresenter
            is FireplaceVideo -> videoPresenter
            else -> spacerPresenter // never bind unknown types as video cards
        }
    }

    override fun getPresenters(): Array<Presenter> = arrayOf(spacerPresenter, videoPresenter)
}
