package com.fireplace.tv.ui

import android.content.Intent
import android.graphics.Rect
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
import com.fireplace.tv.R
import com.fireplace.tv.data.FireplaceVideo
import com.fireplace.tv.data.VideoCatalog

/**
 * D-pad fireplace picker.
 *
 * Inset: ItemDecoration on first/last video (no adapter spacers — those broke
 * Leanback focus search after one RIGHT on TCL). Focus: border + slight Y scale only.
 */
class MainActivity : FragmentActivity() {

    private lateinit var carousel: HorizontalGridView
    private lateinit var objectAdapter: ArrayObjectAdapter
    private var suppressFocusReset: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ViewGroup>(R.id.main_root).apply {
            clipChildren = true
            clipToPadding = true
        }

        val edgePad = dp(EDGE_PAD_DP)
        val edgeInset = dp(EDGE_ITEM_INSET_DP)
        carousel = findViewById(R.id.video_carousel)
        carousel.setNumRows(1)
        carousel.windowAlignment = HorizontalGridView.WINDOW_ALIGN_LOW_EDGE
        carousel.windowAlignmentOffsetPercent = 0f
        carousel.windowAlignmentOffset = 0
        carousel.itemAlignmentOffsetPercent = 0f
        carousel.itemAlignmentOffset = 0
        carousel.clipToPadding = true
        carousel.clipChildren = true
        carousel.setPadding(edgePad, dp(32f), edgePad, dp(32f))
        carousel.isFocusable = true
        carousel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        carousel.horizontalSpacing = dp(20f)

        // Real layout inset on first/last cards (focusable-only adapter).
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
                    val last = state.itemCount - 1
                    if (pos == 0) outRect.left = edgeInset
                    if (pos == last && last >= 0) outRect.right = edgeInset
                }
            },
        )

        Log.i(
            TAG,
            "edgePadDp=$EDGE_PAD_DP edgeInsetDp=$EDGE_ITEM_INSET_DP " +
                "edgePadPx=$edgePad edgeInsetPx=$edgeInset " +
                "density=${resources.displayMetrics.density} focusScaleX=1.0 spacers=false",
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
                        val scaleY = if (hasFocus) FOCUS_SCALE_Y else 1f
                        v.animate().scaleX(1f).scaleY(scaleY).setDuration(120).start()
                        if (hasFocus) {
                            logFocusedScreenBounds(v, viewHolder.bindingAdapterPosition)
                        }
                    }
                    // Do not call smoothScrollToPosition here — it fights DPAD focus search.
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
                }
            },
        )

        // Explicit DPAD navigation so every catalog index is reachable.
        carousel.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveSelection(+1)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveSelection(-1)
                else -> false
            }
        }

        carousel.visibility = View.VISIBLE

        carousel.post {
            focusIndex(0)
            logCarouselMetrics()
        }
        carousel.postDelayed({
            if (carousel.selectedPosition < 0) focusIndex(0)
            logCarouselMetrics()
        }, 300)
    }

    override fun onResume() {
        super.onResume()
        // Don't force-reset to first item — that made browsing feel broken.
        if (::carousel.isInitialized && !suppressFocusReset) {
            carousel.post {
                val pos = carousel.selectedPosition.coerceIn(0, (objectAdapter.size() - 1).coerceAtLeast(0))
                carousel.requestFocus()
                carousel.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
            }
        }
    }

    private fun moveSelection(delta: Int): Boolean {
        val last = objectAdapter.size() - 1
        if (last < 0) return false
        val current = carousel.selectedPosition.coerceAtLeast(0)
        val target = (current + delta).coerceIn(0, last)
        if (target == current) {
            Log.i(TAG, "moveSelection delta=$delta at edge pos=$current")
            return true
        }
        Log.i(TAG, "moveSelection $current -> $target delta=$delta")
        suppressFocusReset = true
        carousel.setSelectedPositionSmooth(target)
        carousel.post {
            carousel.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                ?: carousel.scrollToPosition(target)
        }
        return true
    }

    private fun focusIndex(index: Int) {
        val last = objectAdapter.size() - 1
        if (last < 0) return
        val target = index.coerceIn(0, last)
        carousel.selectedPosition = target
        carousel.requestFocus()
        carousel.scrollToPosition(target)
        carousel.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
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
        val loc = IntArray(2)
        first?.getLocationOnScreen(loc)
        Log.i(
            TAG,
            "carousel w=${carousel.width} h=${carousel.height} " +
                "padL=${carousel.paddingLeft} padR=${carousel.paddingRight} " +
                "childCount=${carousel.childCount} adapterCount=${objectAdapter.size()} " +
                "selected=${carousel.selectedPosition} " +
                "firstScreenX1=${first?.let { loc[0] }}",
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
        private const val EDGE_PAD_DP = 24f
        /** Extra first/last item inset so focused x1 ≳ 80–120 @ density 2. */
        private const val EDGE_ITEM_INSET_DP = 56f
    }
}
