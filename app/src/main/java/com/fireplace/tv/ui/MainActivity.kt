package com.fireplace.tv.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import com.fireplace.tv.R
import com.fireplace.tv.data.FireplaceVideo
import com.fireplace.tv.data.VideoCatalog

/**
 * D-pad fireplace picker using HorizontalGridView + explicit-size custom cards.
 *
 * Prior blank screen causes on TCL:
 * 1) BrowseSupportFragment + HEADERS_DISABLED → title only, rows never shown
 * 2) ImageCardView without explicit LayoutParams / Leanback theme attrs → 0×0 cells
 */
class MainActivity : FragmentActivity() {

    private lateinit var carousel: HorizontalGridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        carousel = findViewById(R.id.video_carousel)
        carousel.setNumRows(1)
        carousel.windowAlignment = HorizontalGridView.WINDOW_ALIGN_LOW_EDGE
        carousel.windowAlignmentOffsetPercent = 0f
        carousel.windowAlignmentOffset = 0
        carousel.itemAlignmentOffsetPercent = 50f
        carousel.clipToPadding = false
        carousel.clipChildren = false
        carousel.isFocusable = true
        carousel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val videos = VideoCatalog.videos
        Log.i(TAG, "Catalog size=${videos.size} titles=${videos.map { it.title }}")

        val objectAdapter = ArrayObjectAdapter(VideoCardPresenter()).apply {
            videos.forEach { add(it) }
        }

        val bridgeAdapter = ItemBridgeAdapter(objectAdapter)
        bridgeAdapter.setAdapterListener(
            object : ItemBridgeAdapter.AdapterListener() {
                override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    viewHolder.itemView.isFocusable = true
                    viewHolder.itemView.isClickable = true
                }

                override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    val video = viewHolder.item as? FireplaceVideo ?: return
                    viewHolder.itemView.setOnClickListener {
                        Log.i(TAG, "Selected ${video.id} ${video.title}")
                        openPlayer(video)
                    }
                    viewHolder.itemView.setOnFocusChangeListener { v, hasFocus ->
                        val scale = if (hasFocus) 1.06f else 1f
                        v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                    }
                }
            },
        )

        carousel.adapter = bridgeAdapter
        carousel.visibility = View.VISIBLE

        carousel.post {
            focusFirstCard(objectAdapter.size())
            logCarouselMetrics(objectAdapter.size())
        }
        // Second pass after layout — MediaTek TV grids sometimes bind late.
        carousel.postDelayed({
            focusFirstCard(objectAdapter.size())
            logCarouselMetrics(objectAdapter.size())
        }, 300)
    }

    override fun onResume() {
        super.onResume()
        if (::carousel.isInitialized) {
            carousel.post {
                val position = carousel.selectedPosition.coerceAtLeast(0)
                carousel.requestFocus()
                carousel.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }

    private fun focusFirstCard(count: Int) {
        if (count <= 0) return
        carousel.selectedPosition = 0
        carousel.requestFocus()
        carousel.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            ?: run {
                // Force a layout pass then focus.
                carousel.scrollToPosition(0)
                carousel.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
    }

    private fun logCarouselMetrics(count: Int) {
        Log.i(
            TAG,
            "carousel w=${carousel.width} h=${carousel.height} " +
                "childCount=${carousel.childCount} adapterCount=$count " +
                "selected=${carousel.selectedPosition}",
        )
    }

    private fun openPlayer(video: FireplaceVideo) {
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_VIDEO_ID, video.id)
            },
        )
    }

    companion object {
        private const val TAG = "FireplaceMain"
    }
}
