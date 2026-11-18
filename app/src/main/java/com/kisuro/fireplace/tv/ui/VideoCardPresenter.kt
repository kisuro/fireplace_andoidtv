package com.kisuro.fireplace.tv.ui

import android.graphics.Outline
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.leanback.widget.Presenter
import com.kisuro.fireplace.tv.R
import com.kisuro.fireplace.tv.data.FireplaceVideo

/**
 * Thumb-only carousel cards under the hero (no title/meta under the strip).
 * Labels for the selection stay on the hero card.
 */
class VideoCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fireplace_card, parent, false)

        val widthPx = parent.resources.getDimensionPixelSize(R.dimen.carousel_card_width)
        view.layoutParams = ViewGroup.MarginLayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                12f,
                parent.resources.displayMetrics,
            ).toInt()
        }
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        val radiusPx = parent.resources.getDimension(R.dimen.card_corner_radius)
        applyRoundClip(view, radiusPx)
        view.findViewById<ImageView>(R.id.card_image)?.let { applyRoundClip(it, radiusPx) }

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val video = unwrapVideo(item) ?: run {
            Log.e(TAG, "Unexpected item type: ${item.javaClass.name}")
            return
        }
        val root = viewHolder.view
        val image = root.findViewById<ImageView>(R.id.card_image)

        image.setImageDrawable(null)
        image.setImageResource(video.thumbnailResId)
        image.tag = video.id
        image.contentDescription = video.title
        root.contentDescription = video.title
        Log.i(
            TAG,
            "bindCard id=${video.id} title=${video.title} " +
                "youtubeId=${video.youtubeId} thumbRes=${video.thumbnailResId}",
        )
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val image = viewHolder.view.findViewById<ImageView>(R.id.card_image) ?: return
        image.setImageDrawable(null)
        image.tag = null
        image.contentDescription = null
        viewHolder.view.contentDescription = null
    }

    private fun applyRoundClip(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }

    private fun unwrapVideo(item: Any): FireplaceVideo? {
        return when (item) {
            is FireplaceVideo -> item
            is CarouselItem.Video -> item.video
            else -> null
        }
    }

    companion object {
        private const val TAG = "FireplaceMain"
    }
}
