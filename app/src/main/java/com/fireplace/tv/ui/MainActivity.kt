package com.fireplace.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import com.fireplace.tv.R
import com.fireplace.tv.data.FireplaceVideo
import com.fireplace.tv.data.VideoCatalog

/**
 * D-pad fireplace picker. Uses [HorizontalGridView] directly instead of
 * [androidx.leanback.app.BrowseSupportFragment], which left only the title
 * visible when headers were disabled (rows never laid out on device).
 */
class MainActivity : FragmentActivity() {

    private lateinit var carousel: HorizontalGridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        carousel = findViewById(R.id.video_carousel)
        carousel.setHasFixedSize(true)
        carousel.windowAlignment = HorizontalGridView.WINDOW_ALIGN_LOW_EDGE
        carousel.windowAlignmentOffsetPercent = 0f
        carousel.windowAlignmentOffset = resources.displayMetrics.density.times(48).toInt()
        carousel.itemAlignmentOffsetPercent = 0f
        carousel.clipToPadding = false
        carousel.clipChildren = false

        val objectAdapter = ArrayObjectAdapter(VideoCardPresenter()).apply {
            VideoCatalog.videos.forEach { add(it) }
        }

        val bridgeAdapter = ItemBridgeAdapter(objectAdapter)
        bridgeAdapter.setAdapterListener(
            object : ItemBridgeAdapter.AdapterListener() {
                override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    viewHolder.itemView.isFocusable = true
                    viewHolder.itemView.isClickable = true
                    viewHolder.itemView.setOnClickListener { view ->
                        val video = viewHolder.item as? FireplaceVideo
                            ?: return@setOnClickListener
                        openPlayer(video)
                    }
                    viewHolder.itemView.setOnKeyListener(null)
                    // Keep scale feedback readable on TV.
                    viewHolder.itemView.setOnFocusChangeListener { v, hasFocus ->
                        val scale = if (hasFocus) 1.08f else 1f
                        v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                    }
                }
            },
        )

        carousel.adapter = bridgeAdapter
        carousel.visibility = View.VISIBLE

        // Focus the first fireplace as soon as the grid is ready.
        carousel.post {
            if (objectAdapter.size() > 0) {
                carousel.selectedPosition = 0
                carousel.requestFocus()
                val holder = carousel.findViewHolderForAdapterPosition(0)
                holder?.itemView?.requestFocus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::carousel.isInitialized && carousel.childCount > 0) {
            carousel.post {
                val position = carousel.selectedPosition.coerceAtLeast(0)
                carousel.requestFocus()
                carousel.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }

    private fun openPlayer(video: FireplaceVideo) {
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_VIDEO_ID, video.id)
            },
        )
    }
}
