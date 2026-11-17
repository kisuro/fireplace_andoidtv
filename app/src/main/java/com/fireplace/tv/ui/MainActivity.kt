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
 * D-pad fireplace picker. Edge cards need large horizontal padding so focus
 * scale/halo is not clipped (TCL v1.0.3 still clipped at ~48px inset).
 */
class MainActivity : FragmentActivity() {

    private lateinit var carousel: HorizontalGridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Disable clipping up the tree (content + decor).
        (window.decorView as? ViewGroup)?.let { decor ->
            decor.clipChildren = false
            decor.clipToPadding = false
            (decor.findViewById<ViewGroup>(android.R.id.content))?.let { content ->
                content.clipChildren = false
                content.clipToPadding = false
            }
        }
        findViewById<ViewGroup>(R.id.main_root).apply {
            clipChildren = false
            clipToPadding = false
        }

        val edgePad = dp(FOCUS_EDGE_PAD_DP)
        carousel = findViewById(R.id.video_carousel)
        carousel.setNumRows(1)
        carousel.windowAlignment = HorizontalGridView.WINDOW_ALIGN_LOW_EDGE
        carousel.windowAlignmentOffsetPercent = 0f
        // Align first item to the padded edge (offset 0 — padding itself is the inset).
        carousel.windowAlignmentOffset = 0
        carousel.itemAlignmentOffsetPercent = 0f
        carousel.itemAlignmentOffset = 0
        carousel.clipToPadding = false
        carousel.clipChildren = false
        carousel.setPadding(edgePad, dp(32f), edgePad, dp(32f))
        carousel.isFocusable = true
        carousel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        carousel.horizontalSpacing = dp(20f)

        Log.i(TAG, "edgePadDp=$FOCUS_EDGE_PAD_DP edgePadPx=$edgePad density=${resources.displayMetrics.density}")

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
                        if (v.width > 0 && v.height > 0) {
                            v.pivotX = v.width / 2f
                            v.pivotY = v.height / 2f
                        }
                        // Modest scale — orange focus stroke on the drawable carries most feedback.
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
        val first = carousel.findViewHolderForAdapterPosition(0)?.itemView
        Log.i(
            TAG,
            "carousel w=${carousel.width} h=${carousel.height} " +
                "padL=${carousel.paddingLeft} padR=${carousel.paddingRight} " +
                "childCount=${carousel.childCount} adapterCount=$count " +
                "selected=${carousel.selectedPosition} " +
                "firstBounds=${first?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" }}",
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
        /** Small zoom; 120dp padding absorbs overflow + focus stroke. */
        private const val FOCUS_SCALE = 1.03f
        private const val FOCUS_EDGE_PAD_DP = 120f
    }
}
