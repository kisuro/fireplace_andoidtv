package com.kisuro.fireplace.tv.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ArrayObjectAdapter
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
 * Edge framing (TCL overscan):
 * - Shared [R.dimen.tv_safe_inset_horizontal] on [R.id.main_root] insets title
 *   and carousel together. Grid-only padding left the header flush and still
 *   lost the first focus stroke under the bezel when overscan ate ~48–56dp.
 * - [WINDOW_ALIGN_BOTH_EDGE] + small focus inset keeps first/last cards framed
 *   inside that safe area (no end void, no spacer items).
 * - Cold start selects index 0 via Leanback selection only — do not call
 *   RecyclerView.scrollToPosition (it is remapped, but double-settle fought layout).
 */
class MainActivity : FragmentActivity() {

    private lateinit var carousel: HorizontalGridView
    private lateinit var objectAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val safeInset = resolveSafeInsetPx()
        val focusInset = resources.getDimensionPixelSize(R.dimen.carousel_focus_inset)
        val padVert = resources.getDimensionPixelSize(R.dimen.carousel_padding_vertical)

        findViewById<ViewGroup>(R.id.main_root).apply {
            clipChildren = true
            clipToPadding = true
            setPadding(safeInset, paddingTop, safeInset, paddingBottom)
        }

        carousel = findViewById(R.id.video_carousel)
        carousel.setNumRows(1)

        // BOTH_EDGE: start → align to left pad; end → align to right pad.
        carousel.windowAlignment = HorizontalGridView.WINDOW_ALIGN_BOTH_EDGE
        carousel.windowAlignmentOffsetPercent = 0f
        carousel.windowAlignmentOffset = 0
        carousel.itemAlignmentOffsetPercent = 0f
        carousel.itemAlignmentOffset = 0
        carousel.clipToPadding = true
        carousel.clipChildren = true
        carousel.setPadding(focusInset, padVert, focusInset, padVert)
        carousel.isFocusable = true
        carousel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        carousel.horizontalSpacing = dp(24f)

        Log.i(
            TAG,
            "align=BOTH_EDGE safeInsetPx=$safeInset focusInsetPx=$focusInset " +
                "density=${resources.displayMetrics.density} widthPx=${resources.displayMetrics.widthPixels}",
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

        // Cold start: wait for layout, then select 0 via Leanback alignment only.
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
            // Leanback GridLayoutManager.setSelection honors window padding/BOTH_EDGE.
            carousel.setSelectedPosition(target)
        }
        carousel.requestFocus()
        carousel.post {
            carousel.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            logCarouselMetrics()
        }
    }

    /**
     * At least the XML safe inset; also never below ~8% of the panel width so
     * dense 1080p / odd density TVs keep the same visible margin.
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
        /** Floor relative to panel width when XML dp under-shoots odd densities. */
        private const val SAFE_INSET_WIDTH_FRACTION = 0.08f
    }
}
