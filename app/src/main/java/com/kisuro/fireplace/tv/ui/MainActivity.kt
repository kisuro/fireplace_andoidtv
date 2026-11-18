package com.kisuro.fireplace.tv.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.recyclerview.widget.RecyclerView
import com.kisuro.fireplace.tv.R
import com.kisuro.fireplace.tv.data.FireplaceVideo
import com.kisuro.fireplace.tv.data.VideoCatalog
import kotlin.math.max

/**
 * D-pad fireplace picker.
 *
 * Left framing (TCL) — v1.0.17 still parked the focused card mostly off-screen.
 *
 * Root cause: [WINDOW_ALIGN_BOTH_EDGE] + large horizontal padding computes
 * `minScroll = minEdge - padding`. Leanback then scrolls so the first (and any
 * left-parked) child's left edge sits at **-padding**, i.e. mostly off-screen.
 * Bigger padding made the clip worse, not better.
 *
 * Fix: [WINDOW_ALIGN_NO_EDGE] with a **window keyline** at the safe inset and
 * item alignment on the child's left edge (percent 0, no padding offset).
 * Every selected card — first, middle, or scrolled to the left — parks its
 * left edge on that keyline, matching the "Fireplace" title indent. No
 * horizontal grid padding (it fought BOTH_EDGE); vertical pad only.
 */
class MainActivity : FragmentActivity() {

    private lateinit var carousel: HorizontalGridView
    private lateinit var objectAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val safeInset = resolveSafeInsetPx()
        val padVert = resources.getDimensionPixelSize(R.dimen.carousel_padding_vertical)

        findViewById<ViewGroup>(R.id.main_root).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(0, paddingTop, 0, paddingBottom)
        }

        findViewById<TextView>(R.id.browse_title).apply {
            val lp = layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.marginStart = safeInset
                lp.marginEnd = safeInset
                layoutParams = lp
            }
        }
        findViewById<TextView>(R.id.browse_subtitle).apply {
            val lp = layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.marginStart = safeInset
                lp.marginEnd = safeInset
                layoutParams = lp
            }
        }

        carousel = findViewById(R.id.video_carousel)
        carousel.setNumRows(1)

        // Keyline at safe inset from the low (left) edge — not BOTH_EDGE+padding.
        carousel.windowAlignment = HorizontalGridView.WINDOW_ALIGN_NO_EDGE
        carousel.windowAlignmentOffsetPercent =
            BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        carousel.windowAlignmentOffset = safeInset
        carousel.itemAlignmentOffsetPercent = 0f
        carousel.itemAlignmentOffset = 0
        carousel.setItemAlignmentOffsetWithPadding(false)
        carousel.clipToPadding = false
        carousel.clipChildren = false
        // Horizontal padding must stay 0 — BOTH_EDGE/padding was the off-screen bug.
        carousel.setPadding(0, padVert, 0, padVert)
        carousel.isFocusable = true
        carousel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        carousel.horizontalSpacing = dp(24f)

        Log.i(
            TAG,
            "align=NO_EDGE keylinePx=$safeInset (windowOffset) " +
                "density=${resources.displayMetrics.density} " +
                "widthPx=${resources.displayMetrics.widthPixels}",
        )

        objectAdapter = ArrayObjectAdapter(VideoCardPresenter()).apply {
            VideoCatalog.videos.forEach { add(it) }
        }

        val bridgeAdapter = ItemBridgeAdapter(objectAdapter)
        bridgeAdapter.setAdapterListener(
            object : ItemBridgeAdapter.AdapterListener() {
                override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    viewHolder.itemView.isFocusable = true
                    viewHolder.itemView.isClickable = true
                    viewHolder.itemView.isFocusableInTouchMode = true
                }

                override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    val video = when (val item = viewHolder.item) {
                        is FireplaceVideo -> item
                        is CarouselItem.Video -> item.video
                        else -> return
                    }
                    viewHolder.itemView.setOnClickListener {
                        Log.i(TAG, "Selected ${video.id} ${video.title}")
                        openPlayer(video)
                    }
                    viewHolder.itemView.setOnFocusChangeListener { v, hasFocus ->
                        if (v.width > 0 && v.height > 0) {
                            v.pivotX = v.width / 2f
                            v.pivotY = v.height / 2f
                        }
                        val scaleY = if (hasFocus) FOCUS_SCALE_Y else 1f
                        v.animate().scaleX(1f).scaleY(scaleY).setDuration(120).start()
                        if (hasFocus) {
                            logFocusedScreenBounds(v, viewHolder.bindingAdapterPosition)
                        }
                    }
                }
            },
        )

        carousel.adapter = bridgeAdapter
        carousel.setOnChildViewHolderSelectedListener(
            object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int,
                ) {
                    Log.i(TAG, "selectedPosition=$position / ${objectAdapter.size() - 1}")
                    child?.itemView?.let { logFocusedScreenBounds(it, position) }
                }
            },
        )

        carousel.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveSelection(+1)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveSelection(-1)
                else -> false
            }
        }

        carousel.visibility = View.VISIBLE

        carousel.post { settleOnIndex(0, smooth = false) }
        carousel.postDelayed({ settleOnIndex(0, smooth = false) }, 200)
    }

    override fun onResume() {
        super.onResume()
        if (!::carousel.isInitialized || !::objectAdapter.isInitialized) return
        carousel.post {
            val last = (objectAdapter.size() - 1).coerceAtLeast(0)
            val pos = carousel.selectedPosition.coerceIn(0, last)
            carousel.requestFocus()
            carousel.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
    }

    private fun moveSelection(delta: Int): Boolean {
        val last = objectAdapter.size() - 1
        if (last < 0) return false
        val current = carousel.selectedPosition.coerceAtLeast(0)
        val target = (current + delta).coerceIn(0, last)
        if (target == current) {
            Log.i(TAG, "moveSelection at edge pos=$current")
            return true
        }
        Log.i(TAG, "moveSelection $current -> $target")
        settleOnIndex(target, smooth = true)
        return true
    }

    private fun settleOnIndex(index: Int, smooth: Boolean) {
        val last = objectAdapter.size() - 1
        if (last < 0) return
        val target = index.coerceIn(0, last)
        if (smooth) {
            carousel.setSelectedPositionSmooth(target)
        } else {
            carousel.setSelectedPosition(target)
        }
        carousel.requestFocus()
        carousel.post {
            carousel.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            logCarouselMetrics()
        }
    }

    /**
     * Safe inset used as the Leanback window keyline (and title margin).
     * Floor at 14% of panel width for TCL-class overscan.
     */
    private fun resolveSafeInsetPx(): Int {
        val fromXml = resources.getDimensionPixelSize(R.dimen.tv_safe_inset_horizontal)
        val fromPercent = (resources.displayMetrics.widthPixels * SAFE_INSET_WIDTH_FRACTION).toInt()
        return max(fromXml, fromPercent)
    }

    private fun logFocusedScreenBounds(view: View, position: Int) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        Log.i(
            TAG,
            "focusedScreen pos=$position x1=${loc[0]} y1=${loc[1]} " +
                "x2=${loc[0] + view.width} y2=${loc[1] + view.height} " +
                "w=${view.width} h=${view.height}",
        )
    }

    private fun logCarouselMetrics() {
        val first = carousel.findViewHolderForAdapterPosition(0)?.itemView
        val lastIdx = objectAdapter.size() - 1
        val last = if (lastIdx >= 0) carousel.findViewHolderForAdapterPosition(lastIdx)?.itemView else null
        val firstLoc = IntArray(2)
        val lastLoc = IntArray(2)
        first?.getLocationOnScreen(firstLoc)
        last?.getLocationOnScreen(lastLoc)
        val root = findViewById<View>(R.id.main_root)
        Log.i(
            TAG,
            "carousel w=${carousel.width} padL=${carousel.paddingLeft} padR=${carousel.paddingRight} " +
                "rootPadL=${root.paddingLeft} rootPadR=${root.paddingRight} " +
                "keylineOffset=${carousel.windowAlignmentOffset} " +
                "adapterCount=${objectAdapter.size()} selected=${carousel.selectedPosition} " +
                "firstX1=${first?.let { firstLoc[0] }} lastX2=${last?.let { lastLoc[0] + it.width }}",
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
        private const val FOCUS_SCALE_Y = 1.02f
        private const val SAFE_INSET_WIDTH_FRACTION = 0.14f
    }
}
