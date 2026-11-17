package com.fireplace.tv.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
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
 * Edge focus clip (TCL): first/last card zoom must stay inside horizontal padding
 * with clipToPadding=false on the grid and ancestors.
 */
class MainActivity : FragmentActivity() {

    private lateinit var carousel: HorizontalGridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ViewGroup>(R.id.main_root).apply {
            clipChildren = false
            clipToPadding = false
        }

        val edgePad = dp(FOCUS_EDGE_PAD_DP)
        carousel = findViewById(R.id.video_carousel)
        carousel.setNumRows(1)
        carousel.windowAlignment = HorizontalGridView.WINDOW_ALIGN_LOW_EDGE
        carousel.windowAlignmentOffsetPercent = 0f
        // Keep the first item inset so focus scale has room on the left.
        carousel.windowAlignmentOffset = edgePad
        carousel.itemAlignmentOffsetPercent = 50f
        carousel.clipToPadding = false
        carousel.clipChildren = false
        carousel.setPadding(edgePad, dp(28f), edgePad, dp(28f))
        carousel.isFocusable = true
        carousel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        carousel.horizontalSpacing = dp(16f)

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
                        // Pivot after layout so WRAP_CONTENT cards scale from center.
                        if (v.width > 0 && v.height > 0) {
                            v.pivotX = v.width / 2f
                            v.pivotY = v.height / 2f
                        }
                        val scale = if (hasFocus) FOCUS_SCALE else 1f
                        v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                        if (hasFocus) {
                            val pos = viewHolder.bindingAdapterPosition
                            if (pos >= 0) {
                                carousel.smoothScrollToPosition(pos)
                            }
                        }
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
        carousel.scrollToPosition(0)
        carousel.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
    }

    private fun logCarouselMetrics(count: Int) {
        Log.i(
            TAG,
            "carousel w=${carousel.width} h=${carousel.height} " +
                "padL=${carousel.paddingLeft} padR=${carousel.paddingRight} " +
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

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        ).toInt()
    }

    companion object {
        private const val TAG = "FireplaceMain"
        private const val FOCUS_SCALE = 1.05f
        /** Enough inset for focus scale on first/last cards (~3% of 320dp + border). */
        private const val FOCUS_EDGE_PAD_DP = 56f
    }
}
