package com.fireplace.tv.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import com.fireplace.tv.R
import com.fireplace.tv.data.FireplaceVideo

/**
 * Plain layout cards (not Leanback ImageCardView) so BaseGridView always gets
 * non-zero measured size even without Theme.Leanback card attributes.
 */
class VideoCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fireplace_card, parent, false)

        val widthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            CARD_WIDTH_DP,
            parent.resources.displayMetrics,
        ).toInt()
        view.layoutParams = ViewGroup.MarginLayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16f,
                parent.resources.displayMetrics,
            ).toInt()
        }
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val video = item as FireplaceVideo
        val root = viewHolder.view
        val image = root.findViewById<ImageView>(R.id.card_image)
        val title = root.findViewById<TextView>(R.id.card_title)
        val meta = root.findViewById<TextView>(R.id.card_meta)

        title.text = video.title
        meta.text = if (video.isLive) {
            root.resources.getString(R.string.live_badge)
        } else {
            "Loop"
        }

        image.load(video.maxThumbnailUrl) {
            placeholder(R.drawable.card_placeholder)
            error(R.drawable.card_placeholder)
            crossfade(true)
            listener(
                onError = { _, _ ->
                    image.load(video.thumbnailUrl) {
                        placeholder(R.drawable.card_placeholder)
                        error(R.drawable.card_placeholder)
                    }
                },
            )
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.findViewById<ImageView>(R.id.card_image)?.setImageDrawable(null)
    }

    companion object {
        private const val CARD_WIDTH_DP = 320f
    }
}
