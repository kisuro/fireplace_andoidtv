package com.fireplace.tv.ui

import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.load
import com.fireplace.tv.R
import com.fireplace.tv.data.FireplaceVideo

class VideoCardPresenter : Presenter() {

    private var defaultCardImage: Drawable? = null
    private var selectedBackground: Int = 0
    private var defaultBackground: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        if (defaultCardImage == null) {
            defaultCardImage = ContextCompat.getDrawable(context, R.drawable.card_placeholder)
            selectedBackground = ContextCompat.getColor(context, R.color.fireplace_focus)
            defaultBackground = ContextCompat.getColor(context, R.color.fireplace_card)
        }

        val cardView = object : ImageCardView(context) {
            override fun setSelected(selected: Boolean) {
                updateCardBackgroundColor(this, selected)
                super.setSelected(selected)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            updateCardBackgroundColor(this, false)
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        }

        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val video = item as FireplaceVideo
        val cardView = viewHolder.view as ImageCardView
        val subtitle = if (video.isLive) {
            cardView.resources.getString(R.string.live_badge)
        } else {
            "Loop"
        }

        cardView.titleText = video.title
        cardView.contentText = subtitle
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.mainImageView?.load(video.maxThumbnailUrl) {
            placeholder(R.drawable.card_placeholder)
            error(R.drawable.card_placeholder)
            crossfade(true)
            listener(
                onError = { _, _ ->
                    cardView.mainImageView?.load(video.thumbnailUrl) {
                        placeholder(R.drawable.card_placeholder)
                        error(R.drawable.card_placeholder)
                    }
                },
            )
        }

        styleCardText(cardView)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
        cardView.mainImage = null
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = if (selected) selectedBackground else defaultBackground
        view.setBackgroundColor(color)
        view.setInfoAreaBackgroundColor(color)
    }

    private fun styleCardText(cardView: ImageCardView) {
        val title = cardView.findViewById<TextView>(androidx.leanback.R.id.title_text)
        val content = cardView.findViewById<TextView>(androidx.leanback.R.id.content_text)
        title?.setTextColor(ContextCompat.getColor(cardView.context, R.color.fireplace_text))
        content?.setTextColor(ContextCompat.getColor(cardView.context, R.color.fireplace_text_dim))
    }

    companion object {
        private const val CARD_WIDTH = 360
        private const val CARD_HEIGHT = 202
    }
}
