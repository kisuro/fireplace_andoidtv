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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.kisuro.fireplace.tv.R
import com.kisuro.fireplace.tv.data.FireplaceVideo
import com.kisuro.fireplace.tv.data.LastPlayedStore
import com.kisuro.fireplace.tv.data.VideoCatalog
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Browse: centered 16:9 hero preview (not focusable) + carousel focus only.
 * Strip selection updates the muted preview; OK on a strip card plays fullscreen.
 */
@UnstableApi
class MainActivity : FragmentActivity() {

    private lateinit var carousel: HorizontalGridView
    private lateinit var objectAdapter: ArrayObjectAdapter
    private lateinit var heroContainer: FrameLayout
    private lateinit var heroRoot: View
    private lateinit var heroMedia: View
    private lateinit var heroImage: ImageView
    private lateinit var heroPlayerView: PlayerView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroPreview: HeroPreviewController

    private var currentVideo: FireplaceVideo? = null
    private var suppressHeroSync: Boolean = false
    private var lastHeroMediaW: Int = -1
    private var lastHeroMediaH: Int = -1

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

        heroContainer = findViewById(R.id.hero_container)
        heroContainer.minimumHeight = resources.getDimensionPixelSize(R.dimen.hero_min_height)

        heroRoot = findViewById(R.id.hero_card)
        heroMedia = heroRoot.findViewById(R.id.hero_media)
        heroImage = heroRoot.findViewById(R.id.hero_image)
        heroPlayerView = heroRoot.findViewById(R.id.hero_player)
        heroTitle = heroRoot.findViewById(R.id.hero_title)
        heroMeta = heroRoot.findViewById(R.id.hero_meta)

        // Focus never lands on the hero — display-only preview; DPAD stays on strip.
        heroRoot.isFocusable = false
        heroRoot.isFocusableInTouchMode = false
        heroRoot.isClickable = false
        heroMedia.isFocusable = false
        heroPlayerView.isFocusable = false
        heroPlayerView.isClickable = false

        val radiusPx = resources.getDimension(R.dimen.card_corner_radius)
        applyRoundClip(heroRoot, radiusPx)
        applyRoundClip(heroMedia, radiusPx)

        heroContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            sizeHeroToSixteenNine()
        }
        heroContainer.post { sizeHeroToSixteenNine() }

        heroPreview = HeroPreviewController(
            context = this,
            lifecycleOwner = this,
            playerView = heroPlayerView,
            poster = heroImage,
        )

        carousel = findViewById(R.id.video_carousel)
        carousel.setNumRows(1)
        carousel.windowAlignment = HorizontalGridView.WINDOW_ALIGN_NO_EDGE
        carousel.windowAlignmentOffsetPercent = 50f
        carousel.windowAlignmentOffset = 0
        carousel.itemAlignmentOffsetPercent = 50f
        carousel.itemAlignmentOffset = 0
        carousel.setItemAlignmentOffsetWithPadding(false)
        carousel.clipToPadding = false
        carousel.clipChildren = false
        carousel.setPadding(0, dp(6f), 0, dp(6f))
        carousel.isFocusable = true
        carousel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        carousel.horizontalSpacing = dp(10f)

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

        // DPAD stays on the strip — UP must not move focus to the hero.
        carousel.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveSelection(+1)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveSelection(-1)
                KeyEvent.KEYCODE_DPAD_UP -> true // swallow — keep focus on carousel
                else -> false
            }
        }

        val initial = LastPlayedStore.resolveVideo(this)
        val initialIndex = VideoCatalog.videos.indexOfFirst { it.id == initial.id }.coerceAtLeast(0)
        Log.i(TAG, "restore lastPlayed id=${initial.id} index=$initialIndex")
        bindHero(initial)
        carousel.post {
            settleOnIndex(initialIndex, smooth = false)
        }
        carousel.postDelayed({
            settleOnIndex(initialIndex, smooth = false)
        }, 200)
    }

    override fun onResume() {
        super.onResume()
        if (!::carousel.isInitialized) return
        val restored = LastPlayedStore.resolveVideo(this)
        val index = VideoCatalog.videos.indexOfFirst { it.id == restored.id }.coerceAtLeast(0)
        bindHero(restored)
        if (::heroPreview.isInitialized) {
            heroPreview.resume(restored)
        }
        carousel.post {
            settleOnIndex(index, smooth = false)
        }
    }

    override fun onPause() {
        if (::heroPreview.isInitialized) {
            heroPreview.pause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::heroPreview.isInitialized) {
            heroPreview.release()
        }
        super.onDestroy()
    }

    /**
     * Fit a 16:9 media box inside [heroContainer], capped so it is not near
     * full-bleed. Card chrome (padding + title row) sits under the media.
     */
    private fun sizeHeroToSixteenNine() {
        val hostW = heroContainer.width
        val hostH = heroContainer.height
        if (hostW <= 0 || hostH <= 0) return

        val cardPad = dp(16f) // 8dp * 2
        val titleChrome = dp(44f)
        val maxMediaW = min(
            hostW - dp(24f),
            (resources.displayMetrics.widthPixels * HERO_MAX_WIDTH_FRACTION).roundToInt(),
        ).coerceAtLeast(dp(280f))
        val maxMediaH = (hostH - cardPad - titleChrome).coerceAtLeast(dp(140f))

        var mediaW = maxMediaW
        var mediaH = (mediaW * 9f / 16f).roundToInt()
        if (mediaH > maxMediaH) {
            mediaH = maxMediaH
            mediaW = (mediaH * 16f / 9f).roundToInt()
        }

        if (mediaW == lastHeroMediaW && mediaH == lastHeroMediaH) return
        lastHeroMediaW = mediaW
        lastHeroMediaH = mediaH

        heroMedia.layoutParams = heroMedia.layoutParams.apply {
            width = mediaW
            height = mediaH
        }
        val cardLp = (heroRoot.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        cardLp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        cardLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        cardLp.gravity = android.view.Gravity.CENTER
        heroRoot.layoutParams = cardLp
        heroRoot.requestLayout()
        Log.i(TAG, "hero 16:9 media=${mediaW}x$mediaH host=${hostW}x$hostH")
    }

    private fun bindHero(video: FireplaceVideo) {
        currentVideo = video
        heroImage.setImageResource(video.thumbnailResId)
        heroImage.visibility = View.VISIBLE
        heroTitle.text = video.title
        heroMeta.text = if (video.isLive) {
            getString(R.string.live_badge)
        } else {
            "Loop"
        }
        heroRoot.contentDescription = video.title
        Log.i(TAG, "hero id=${video.id} title=${video.title}")
        if (::heroPreview.isInitialized) {
            heroPreview.preview(video)
        }
    }

    private fun moveSelection(delta: Int): Boolean {
        val last = objectAdapter.size() - 1
        if (last < 0) return false
        val current = carousel.selectedPosition.coerceAtLeast(0)
        val target = (current + delta).coerceIn(0, last)
        if (target == current) return true
        settleOnIndex(target, smooth = true)
        return true
    }

    private fun settleOnIndex(index: Int, smooth: Boolean) {
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
            carousel.requestFocus()
            carousel.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
        }
    }

    private fun openPlayer(video: FireplaceVideo) {
        LastPlayedStore.setVideoId(this, video.id)
        if (::heroPreview.isInitialized) {
            heroPreview.release()
        }
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
        private const val CAROUSEL_FOCUS_SCALE_Y = 1.03f
        private const val SAFE_INSET_WIDTH_FRACTION = 0.07f
        /** Cap hero media width so the 16:9 box stays a preview, not near full-bleed. */
        private const val HERO_MAX_WIDTH_FRACTION = 0.52f
    }
}
