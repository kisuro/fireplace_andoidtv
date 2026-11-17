package com.fireplace.tv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fireplace.tv.R
import com.fireplace.tv.data.VideoCatalog

/**
 * D-pad fireplace picker.
 *
 * TCL MT5898: HorizontalGridView LOW_EDGE + focus scale put the first card at
 * screen x=0 despite padL=240. Fix = non-focusable edge spacers + no X scale
 * (border-only focus) + clipToPadding.
 */
class MainActivity : FragmentActivity() {

    private lateinit var carousel: HorizontalGridView
    private var firstVideoAdapterIndex: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ViewGroup>(R.id.main_root).apply {
            clipChildren = true
            clipToPadding = true
        }

        val edgePad = dp(EDGE_PAD_DP)
        carousel = findViewById(R.id.video_carousel)
        carousel.setNumRows(1)
        carousel.windowAlignment = HorizontalGridView.WINDOW_ALIGN_LOW_EDGE
        carousel.windowAlignmentOffsetPercent = 0f
        carousel.windowAlignmentOffset = 0
        carousel.itemAlignmentOffsetPercent = 0f
        carousel.itemAlignmentOffset = 0
        // Keep scaled/halo drawing inside padded area; spacers provide the real inset.
        carousel.clipToPadding = true
        carousel.clipChildren = true
        carousel.setPadding(edgePad, dp(32f), edgePad, dp(32f))
        carousel.isFocusable = true
        carousel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        carousel.horizontalSpacing = dp(20f)

        // Extra offsets on first/last *video* rows as belt-and-suspenders
        // (spacers are adapter positions 0 and last).
        carousel.addItemDecoration(
            object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State,
                ) {
                    val pos = parent.getChildAdapterPosition(view)
                    if (pos == RecyclerView.NO_POSITION) return
                    val count = state.itemCount
                    // Spacers already take width; no extra on spacer positions.
                    if (pos == 0 || pos == count - 1) return
                }
            },
        )

        Log.i(
            TAG,
            "edgePadDp=$EDGE_PAD_DP edgePadPx=$edgePad spacerDp=${EdgeSpacerPresenter.WIDTH_DP} " +
                "density=${resources.displayMetrics.density} focusScaleX=1.0",
        )

        val objectAdapter = ArrayObjectAdapter(CarouselPresenterSelector()).apply {
            add(CarouselItem.EdgeSpacer)
            VideoCatalog.videos.forEach { add(CarouselItem.Video(it)) }
            add(CarouselItem.EdgeSpacer)
        }
        firstVideoAdapterIndex = 1

        val bridgeAdapter = ItemBridgeAdapter(objectAdapter)
        bridgeAdapter.setAdapterListener(
            object : ItemBridgeAdapter.AdapterListener() {
                override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    val item = viewHolder.item
                    if (item is CarouselItem.EdgeSpacer) {
                        viewHolder.itemView.isFocusable = false
                        viewHolder.itemView.isClickable = false
                        return
                    }
                    viewHolder.itemView.isFocusable = true
                    viewHolder.itemView.isClickable = true
                }

                override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    val item = viewHolder.item as? CarouselItem.Video ?: return
                    viewHolder.itemView.setOnClickListener {
                        Log.i(TAG, "Selected ${item.video.id} ${item.video.title}")
                        openPlayer(item.video)
                    }
                    viewHolder.itemView.setOnFocusChangeListener { v, hasFocus ->
                        // Border drawable shows focus; optional tiny Y-only lift (no X scale).
                        if (v.width > 0 && v.height > 0) {
                            v.pivotX = v.width / 2f
                            v.pivotY = v.height / 2f
                        }
                        val scaleY = if (hasFocus) FOCUS_SCALE_Y else 1f
                        v.animate()
                            .scaleX(1f)
                            .scaleY(scaleY)
                            .setDuration(120)
                            .start()
                        if (hasFocus) {
                            val pos = viewHolder.bindingAdapterPosition
                            if (pos >= 0) carousel.smoothScrollToPosition(pos)
                            logFocusedScreenBounds(v)
                        }
                    }
                }
            },
        )

        carousel.adapter = bridgeAdapter
        carousel.visibility = View.VISIBLE

        carousel.post {
            focusFirstVideo()
            logCarouselMetrics(objectAdapter.size())
        }
        carousel.postDelayed({
            focusFirstVideo()
            logCarouselMetrics(objectAdapter.size())
        }, 300)
    }

    override fun onResume() {
        super.onResume()
        if (::carousel.isInitialized) {
            carousel.post { focusFirstVideo() }
        }
    }

    private fun focusFirstVideo() {
        val index = firstVideoAdapterIndex
        carousel.selectedPosition = index
        carousel.requestFocus()
        carousel.scrollToPosition(index)
        carousel.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
    }

    private fun logFocusedScreenBounds(view: View) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        Log.i(
            TAG,
            "focusedScreen x1=${loc[0]} y1=${loc[1]} " +
                "x2=${loc[0] + view.width} y2=${loc[1] + view.height} " +
                "w=${view.width} h=${view.height}",
        )
    }

    private fun logCarouselMetrics(count: Int) {
        val firstVideo = carousel.findViewHolderForAdapterPosition(firstVideoAdapterIndex)?.itemView
        val loc = IntArray(2)
        firstVideo?.getLocationOnScreen(loc)
        Log.i(
            TAG,
            "carousel w=${carousel.width} h=${carousel.height} " +
                "padL=${carousel.paddingLeft} padR=${carousel.paddingRight} " +
                "childCount=${carousel.childCount} adapterCount=$count " +
                "selected=${carousel.selectedPosition} " +
                "firstVideoLocal=${firstVideo?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" }} " +
                "firstVideoScreenX1=${firstVideo?.let { loc[0] }}",
        )
    }

    private fun openPlayer(video: com.fireplace.tv.data.FireplaceVideo) {
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
        /** No horizontal zoom — border-only focus (prevents x=0 overflow). */
        private const val FOCUS_SCALE_Y = 1.02f
        private const val EDGE_PAD_DP = 24f
    }
}
