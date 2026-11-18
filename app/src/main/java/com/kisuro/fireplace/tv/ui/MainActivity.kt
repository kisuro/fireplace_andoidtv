package com.kisuro.fireplace.tv.ui

import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
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
import com.kisuro.fireplace.tv.data.LastPlayedStore
import com.kisuro.fireplace.tv.data.VideoCatalog
import kotlin.math.max

/**
 * Browse: large centered hero for the current fireplace + smaller carousel
 * underneath. Carousel selection updates the hero; OK on hero or a strip card
 * starts playback. Last-played id is restored from [LastPlayedStore].
 *
 * Keeps v1.0.19 polish: 80dp/≥7% keyline margins, 14dp rounded cards.
 */
class MainActivity : FragmentActivity() {

    private lateinit var carousel: HorizontalGridView
    private lateinit var objectAdapter: ArrayObjectAdapter
    private lateinit var heroRoot: View
    private lateinit var heroImage: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView

    private var currentVideo: FireplaceVideo? = null
    private var suppressHeroSync: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val safeInset = resolveSafeInsetPx()

        findViewById<ViewGroup>(R.id.main_root).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(0, paddingTop, 0, paddingBottom)
        }

        applyHorizontalInset(findViewById(R.id.browse_title), safeInset)
        applyHorizontalInset(findViewById(R.id.browse_subtitle), safeInset)

        // hero_container is the weighted FrameLayout (must stay non-zero height).
        // Insets apply to the hero card; carousel uses a fixed row height so Leanback
        // wrap measure cannot steal the leftover space (v1.0.20 collapsed hero to 0).
        findViewById<View>(R.id.hero_container).minimumHeight =
            resources.getDimensionPixelSize(R.dimen.hero_min_height)

        heroRoot = findViewById(R.id.hero_card)
        heroImage = heroRoot.findViewById(R.id.hero_image)
        heroTitle = heroRoot.findViewById(R.id.hero_title)
        heroMeta = heroRoot.findViewById(R.id.hero_meta)
        applyHorizontalInset(heroRoot, safeInset)

        val radiusPx = resources.getDimension(R.dimen.card_corner_radius)
        applyRoundClip(heroRoot, radiusPx)
        applyRoundClip(heroImage, radiusPx)

        heroRoot.setOnClickListener {
            currentVideo?.let { openPlayer(it) }
        }
        heroRoot.setOnFocusChangeListener { v, hasFocus ->
            if (v.width > 0 && v.height > 0) {
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
            }
            val scale = if (hasFocus) HERO_FOCUS_SCALE else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }

        carousel = findViewById(R.id.video_carousel)
        carousel.setNumRows(1)
        // Center the selected small card under the hero.
        carousel.windowAlignment = HorizontalGridView.WINDOW_ALIGN_NO_EDGE
        carousel.windowAlignmentOffsetPercent = 50f
        carousel.windowAlignmentOffset = 0
        carousel.itemAlignmentOffsetPercent = 50f
        carousel.itemAlignmentOffset = 0
        carousel.setItemAlignmentOffsetWithPadding(false)
        carousel.clipToPadding = false
        carousel.clipChildren = false
        carousel.setPadding(0, dp(8f), 0, dp(8f))
        carousel.isFocusable = true
        carousel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        carousel.horizontalSpacing = dp(12f)

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
                        bindHero(video)
                        openPlayer(video)
                    }
                    viewHolder.itemView.setOnFocusChangeListener { v, hasFocus ->
                        if (v.width > 0 && v.height > 0) {
                            v.pivotX = v.width / 2f
                            v.pivotY = v.height / 2f
                        }
                        val scaleY = if (hasFocus) CAROUSEL_FOCUS_SCALE_Y else 1f
                        v.animate().scaleX(1f).scaleY(scaleY).setDuration(120).start()
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
                    if (suppressHeroSync) return
                    val video = objectAdapter.get(position) as? FireplaceVideo ?: return
                    bindHero(video)
                }
            },
        )

        carousel.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveSelection(+1)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveSelection(-1)
                KeyEvent.KEYCODE_DPAD_UP -> {
                    heroRoot.requestFocus()
                    true
                }
                else -> false
            }
        }

        heroRoot.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    carousel.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> moveSelection(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveSelection(+1)
                else -> false
            }
        }

        val initial = LastPlayedStore.resolveVideo(this)
        val initialIndex = VideoCatalog.videos.indexOfFirst { it.id == initial.id }.coerceAtLeast(0)
        Log.i(TAG, "restore lastPlayed id=${initial.id} index=$initialIndex")
        bindHero(initial)
        carousel.post {
            settleOnIndex(initialIndex, smooth = false, focusHero = true)
        }
        carousel.postDelayed({
            settleOnIndex(initialIndex, smooth = false, focusHero = true)
        }, 200)
    }

    override fun onResume() {
        super.onResume()
        if (!::carousel.isInitialized) return
        val restored = LastPlayedStore.resolveVideo(this)
        val index = VideoCatalog.videos.indexOfFirst { it.id == restored.id }.coerceAtLeast(0)
        bindHero(restored)
        carousel.post {
            settleOnIndex(index, smooth = false, focusHero = true)
        }
    }

    private fun bindHero(video: FireplaceVideo) {
        currentVideo = video
        heroImage.setImageDrawable(null)
        heroImage.setImageResource(video.thumbnailResId)
        heroTitle.text = video.title
        heroMeta.text = if (video.isLive) {
            getString(R.string.live_badge)
        } else {
            "Loop"
        }
        heroRoot.contentDescription = video.title
        Log.i(TAG, "hero id=${video.id} title=${video.title}")
    }

    private fun moveSelection(delta: Int): Boolean {
        val last = objectAdapter.size() - 1
        if (last < 0) return false
        val current = carousel.selectedPosition.coerceAtLeast(0)
        val target = (current + delta).coerceIn(0, last)
        if (target == current) return true
        settleOnIndex(target, smooth = true, focusHero = false)
        return true
    }

    private fun settleOnIndex(index: Int, smooth: Boolean, focusHero: Boolean) {
        val last = objectAdapter.size() - 1
        if (last < 0) return
        val target = index.coerceIn(0, last)
        suppressHeroSync = true
        if (smooth) {
            carousel.setSelectedPositionSmooth(target)
        } else {
            carousel.setSelectedPosition(target)
        }
        val video = objectAdapter.get(target) as? FireplaceVideo
        if (video != null) bindHero(video)
        carousel.post {
            suppressHeroSync = false
            if (focusHero) {
                heroRoot.requestFocus()
            } else {
                carousel.requestFocus()
                carousel.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            }
        }
    }

    private fun openPlayer(video: FireplaceVideo) {
        LastPlayedStore.setVideoId(this, video.id)
        Log.i(TAG, "play id=${video.id} title=${video.title}")
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_VIDEO_ID, video.id)
            },
        )
    }

    private fun resolveSafeInsetPx(): Int {
        val fromXml = resources.getDimensionPixelSize(R.dimen.tv_safe_inset_horizontal)
        val fromPercent = (resources.displayMetrics.widthPixels * SAFE_INSET_WIDTH_FRACTION).toInt()
        return max(fromXml, fromPercent)
    }

    private fun applyHorizontalInset(view: View, insetPx: Int) {
        val lp = view.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.marginStart = insetPx
            lp.marginEnd = insetPx
            view.layoutParams = lp
        }
    }

    private fun applyRoundClip(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
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
        private const val HERO_FOCUS_SCALE = 1.015f
        private const val CAROUSEL_FOCUS_SCALE_Y = 1.03f
        private const val SAFE_INSET_WIDTH_FRACTION = 0.07f
    }
}
