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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.kisuro.fireplace.tv.R
import com.kisuro.fireplace.tv.data.AudioPrefsStore
import com.kisuro.fireplace.tv.data.CatalogPrefsStore
import com.kisuro.fireplace.tv.data.FireplaceVideo
import com.kisuro.fireplace.tv.data.LastPlayedStore
import com.kisuro.fireplace.tv.data.VideoCatalog
import com.kisuro.fireplace.tv.extract.YoutubeStreamResolver
import com.kisuro.fireplace.tv.playback.AmbientSoundController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Browse: centered 16:9 hero preview (not focusable) + carousel focus only.
 * Strip selection updates the muted preview.
 * Sound chip opens ambient / fireplace-crackle prefs (ambient off by default).
 *
 * Favorites / filter / reorder:
 * - Short OK opens card menu: Play, Add/Remove ♥, Move, Cancel.
 * - Long-press OK quick-toggles favorite (heart badge).
 * - Move mode: Left/Right walks the card to a new index (key-intercepted so Leanback
 *   cannot steal DPAD); OK drops; Back cancels.
 * - Segmented All | Favorites filter (persisted).
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
    private lateinit var browseSubtitle: TextView
    private lateinit var filterAll: TextView
    private lateinit var filterFavorites: TextView
    private lateinit var soundOpenButton: TextView
    private lateinit var ambientStatus: TextView
    private lateinit var emptyFavorites: LinearLayout
    private lateinit var emptyShowAllChip: TextView
    private lateinit var soundPanel: SoundPanelController
    private lateinit var cardMenu: CardActionMenuController

    private var currentVideo: FireplaceVideo? = null
    private var suppressHeroSync: Boolean = false
    private var lastHeroMediaW: Int = -1
    private var lastHeroMediaH: Int = -1

    /** When non-null, carousel is in reorder mode; value is the order snapshot to restore on cancel. */
    private var moveOrderSnapshot: List<String>? = null

    /** Id of the card being walked in move mode (stays under focus as it travels). */
    private var movingVideoId: String? = null

    private val isMoveMode: Boolean
        get() = moveOrderSnapshot != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val safeInset = resolveSafeInsetPx()

        findViewById<ViewGroup>(R.id.main_content).apply {
            clipChildren = false
            clipToPadding = false
        }

        applyHorizontalInset(findViewById(R.id.browse_title), safeInset)
        applyHorizontalInset(findViewById(R.id.browse_header_row), safeInset)
        applyHorizontalInset(findViewById(R.id.ambient_status), safeInset)

        browseSubtitle = findViewById(R.id.browse_subtitle)
        filterAll = findViewById(R.id.filter_all)
        filterFavorites = findViewById(R.id.filter_favorites)
        soundOpenButton = findViewById(R.id.sound_open_button)
        ambientStatus = findViewById(R.id.ambient_status)
        emptyFavorites = findViewById(R.id.empty_favorites)
        emptyShowAllChip = findViewById(R.id.empty_show_all_chip)
        soundPanel = SoundPanelController(findViewById(R.id.main_root)) {
            applyAudioPrefs()
        }
        cardMenu = CardActionMenuController(
            host = findViewById(R.id.main_root),
            onPlay = { openPlayer(it) },
            onToggleFavorite = { toggleFavoriteForVideo(it) },
            onMove = { enterMoveModeFor(it) },
        )
        soundOpenButton.setOnClickListener { soundPanel.show() }
        filterAll.setOnClickListener { setFavoritesFilter(false) }
        filterFavorites.setOnClickListener { setFavoritesFilter(true) }
        emptyShowAllChip.setOnClickListener {
            CatalogPrefsStore.setFavoritesOnly(this, false)
            reloadCarousel(preferId = currentVideo?.id)
        }

        wireChipFocus(filterAll, filterFavorites, soundOpenButton)

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

        objectAdapter = ArrayObjectAdapter(VideoCardPresenter())
        val bridgeAdapter = ItemBridgeAdapter(objectAdapter)
        bridgeAdapter.setAdapterListener(
            object : ItemBridgeAdapter.AdapterListener() {
                override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    viewHolder.itemView.isFocusable = true
                    viewHolder.itemView.isClickable = true
                    viewHolder.itemView.isFocusableInTouchMode = true
                    viewHolder.itemView.isLongClickable = true
                }

                override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    val video = when (val item = viewHolder.item) {
                        is FireplaceVideo -> item
                        is CarouselItem.Video -> item.video
                        else -> return
                    }
                    viewHolder.itemView.setOnClickListener {
                        if (isMoveMode) {
                            exitMoveMode(commit = true)
                            return@setOnClickListener
                        }
                        if (cardMenu.isVisible || soundPanel.isVisible) return@setOnClickListener
                        bindHero(video)
                        cardMenu.show(video, canMove = objectAdapter.size() >= 2)
                    }
                    viewHolder.itemView.setOnLongClickListener {
                        if (isMoveMode || cardMenu.isVisible) return@setOnLongClickListener true
                        bindHero(video)
                        toggleFavoriteForVideo(video)
                        true
                    }
                    viewHolder.itemView.setOnFocusChangeListener { v, hasFocus ->
                        if (v.width > 0 && v.height > 0) {
                            v.pivotX = v.width / 2f
                            v.pivotY = v.height / 2f
                        }
                        val scaleY = when {
                            hasFocus && isMoveMode -> CAROUSEL_MOVE_SCALE_Y
                            hasFocus -> CAROUSEL_FOCUS_SCALE_Y
                            else -> 1f
                        }
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

        // Intercept keys BEFORE the focused card / Leanback selection consumes them.
        // setOnKeyListener on the grid never sees DPAD while a child holds focus — that
        // made Move mode appear broken (selection moved without swapping the card).
        carousel.setOnKeyInterceptListener(
            BaseGridView.OnKeyInterceptListener { event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyInterceptListener false
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        when {
                            cardMenu.isVisible -> true
                            isMoveMode -> reorderSelection(+1)
                            else -> false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        when {
                            cardMenu.isVisible -> true
                            isMoveMode -> reorderSelection(-1)
                            else -> false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (isMoveMode || cardMenu.isVisible) return@OnKeyInterceptListener true
                        focusActiveFilterSegment()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A,
                    -> {
                        if (!isMoveMode) return@OnKeyInterceptListener false
                        if (event.repeatCount == 0) {
                            exitMoveMode(commit = true)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        if (isMoveMode || cardMenu.isVisible) return@OnKeyInterceptListener true
                        soundPanel.toggle()
                        true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        when {
                            cardMenu.isVisible -> {
                                cardMenu.hide()
                                true
                            }
                            isMoveMode -> {
                                exitMoveMode(commit = false)
                                true
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            },
        )

        reloadCarousel(preferId = LastPlayedStore.getVideoId(this))

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        cardMenu.isVisible -> {
                            cardMenu.hide()
                            focusCurrentCard()
                        }
                        isMoveMode -> exitMoveMode(commit = false)
                        soundPanel.isVisible -> {
                            soundPanel.hide()
                            focusCurrentCard()
                        }
                        else -> finish()
                    }
                }
            },
        )

        // Show strip immediately; probe YouTube availability in parallel and drop dead tiles.
        startAvailabilityProbe()
        applyAudioPrefs()
    }

    /**
     * Parallel NewPipe probes (capped concurrency). Confirmed-unavailable entries are
     * removed from the carousel/hero; transient network/bot errors keep the tile.
     */
    private fun startAvailabilityProbe() {
        val resolver = YoutubeStreamResolver()
        val catalog = VideoCatalog.videos.toList()
        lifecycleScope.launch {
            val gate = Semaphore(AVAILABILITY_PARALLELISM)
            Log.i(TAG, "availability probe start count=${catalog.size} parallel=$AVAILABILITY_PARALLELISM")
            catalog.map { video ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val ok = resolver.probeAvailable(video.watchUrl, expectLive = video.isLive)
                        video.id to ok
                    }
                }
            }.awaitAll().forEach { (id, ok) ->
                if (!ok) {
                    withContext(Dispatchers.Main.immediate) {
                        hideUnavailableVideo(id)
                    }
                }
            }
            Log.i(TAG, "availability probe done visible=${VideoCatalog.visibleVideos.size}")
        }
    }

    private fun hideUnavailableVideo(id: String) {
        if (!VideoCatalog.markUnavailable(id)) return
        val index = indexOfVideo(id)
        if (index < 0) {
            Log.i(TAG, "hide unavailable id=$id (already absent from strip)")
            return
        }
        Log.w(TAG, "hide unavailable id=$id index=$index title=${(objectAdapter.get(index) as? FireplaceVideo)?.title}")
        objectAdapter.removeItems(index, 1)
        if (objectAdapter.size() == 0) {
            currentVideo = null
            applyEmptyOrHeroState()
            return
        }
        if (currentVideo?.id == id) {
            val next = LastPlayedStore.resolveVideo(this)
            val nextIndex = indexOfVideo(next.id).coerceAtLeast(0)
            bindHero(next)
            settleOnIndex(nextIndex, smooth = false)
        } else {
            val selected = carousel.selectedPosition
            if (selected >= objectAdapter.size()) {
                settleOnIndex(objectAdapter.size() - 1, smooth = false)
            }
        }
        updateChrome()
    }

    private fun indexOfVideo(id: String): Int {
        for (i in 0 until objectAdapter.size()) {
            val item = objectAdapter.get(i)
            val video = item as? FireplaceVideo ?: (item as? CarouselItem.Video)?.video
            if (video?.id == id) return i
        }
        return -1
    }

    override fun onResume() {
        super.onResume()
        // Ambient is process-scoped — always refresh status/prefs on resume
        // (including empty favorites / move mode); do not pause on activity pause.
        applyAudioPrefs()
        if (!::carousel.isInitialized) return
        if (isMoveMode) return
        val browse = VideoCatalog.browseVideos(this)
        if (browse.isEmpty()) {
            applyEmptyOrHeroState()
            return
        }
        val restored = LastPlayedStore.resolveVideo(this)
        val index = indexOfVideo(restored.id).coerceAtLeast(0)
        bindHero(restored)
        if (::heroPreview.isInitialized) {
            heroPreview.resume(restored)
        }
        updateChrome()
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

    private fun applyAudioPrefs() {
        AmbientSoundController.sync(this)
        if (!::ambientStatus.isInitialized) return
        ambientStatus.text = if (AudioPrefsStore.isAmbientEnabled(this)) {
            val sound = AudioPrefsStore.getAmbientSound(this)
            getString(R.string.sound_status_on, getString(sound.titleRes))
        } else {
            getString(R.string.sound_status_off)
        }
    }

    private fun setFavoritesFilter(favoritesOnly: Boolean) {
        if (isMoveMode) exitMoveMode(commit = true)
        if (CatalogPrefsStore.isFavoritesOnly(this) == favoritesOnly) {
            updateChrome()
            return
        }
        CatalogPrefsStore.setFavoritesOnly(this, favoritesOnly)
        reloadCarousel(preferId = currentVideo?.id)
    }

    private fun toggleFavoriteForVideo(video: FireplaceVideo) {
        if (isMoveMode) return
        val favored = CatalogPrefsStore.toggleFavorite(this, video.id)
        Toast.makeText(
            this,
            if (favored) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT,
        ).show()
        if (CatalogPrefsStore.isFavoritesOnly(this) && !favored) {
            if (cardMenu.isVisible) cardMenu.hide()
            reloadCarousel(preferId = null)
        } else {
            objectAdapter.notifyItemRangeChanged(0, objectAdapter.size())
            updateChrome()
        }
    }

    private fun enterMoveModeFor(video: FireplaceVideo) {
        if (objectAdapter.size() < 2) {
            Toast.makeText(this, R.string.card_menu_move, Toast.LENGTH_SHORT).show()
            return
        }
        if (cardMenu.isVisible) cardMenu.hide()
        bindHero(video)
        movingVideoId = video.id
        moveOrderSnapshot = CatalogPrefsStore.resolveOrder(this, VideoCatalog.videos.map { it.id })
        updateChrome()
        val index = indexOfVideo(video.id).coerceAtLeast(0)
        // Post so focus lands after the menu overlay is gone.
        carousel.post {
            settleOnIndex(index, smooth = false)
            Toast.makeText(this, R.string.browse_move_hint, Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "move mode enter focus=${video.id} index=$index")
    }

    private fun exitMoveMode(commit: Boolean) {
        val snapshot = moveOrderSnapshot ?: return
        val preferId = movingVideoId ?: currentVideo?.id
        if (!commit) {
            CatalogPrefsStore.setOrder(this, snapshot)
            moveOrderSnapshot = null
            movingVideoId = null
            reloadCarousel(preferId = preferId)
            Log.i(TAG, "move mode cancel")
            return
        }
        // Belt-and-suspenders: rewrite prefs from the live adapter order.
        persistAdapterBrowseOrder()
        moveOrderSnapshot = null
        movingVideoId = null
        updateChrome()
        focusCurrentCard()
        Log.i(
            TAG,
            "move mode commit prefer=$preferId pos=${carousel.selectedPosition} " +
                "order=${CatalogPrefsStore.resolveOrder(this, VideoCatalog.videos.map { it.id })}",
        )
    }

    /**
     * Walk the focused card one slot Left/Right by swapping with its neighbor so the
     * same fire stays under focus and the user sees the landing index live.
     */
    private fun reorderSelection(delta: Int): Boolean {
        val last = objectAdapter.size() - 1
        if (last < 1) return true
        val movingId = movingVideoId
        val current = if (movingId != null) {
            indexOfVideo(movingId).takeIf { it >= 0 } ?: carousel.selectedPosition.coerceAtLeast(0)
        } else {
            carousel.selectedPosition.coerceAtLeast(0)
        }
        val target = (current + delta).coerceIn(0, last)
        if (target == current) return true
        val a = objectAdapter.get(current) as? FireplaceVideo ?: return true
        val b = objectAdapter.get(target) as? FireplaceVideo ?: return true
        if (movingId != null && a.id != movingId) {
            settleOnIndex(current, smooth = false)
            return true
        }
        // Swap adapter slots first so the UI shows the card traveling with focus.
        objectAdapter.replace(current, b)
        objectAdapter.replace(target, a)
        CatalogPrefsStore.swapInOrder(this, a.id, b.id)
        persistAdapterBrowseOrder()
        movingVideoId = a.id
        settleOnIndex(target, smooth = true)
        Log.i(TAG, "reorder walk ${a.id} $current->$target (swap ${b.id})")
        return true
    }

    /** Persist visible strip order into catalog prefs (keeps non-visible ids in place). */
    private fun persistAdapterBrowseOrder() {
        val browseIds = ArrayList<String>(objectAdapter.size())
        for (i in 0 until objectAdapter.size()) {
            val video = objectAdapter.get(i) as? FireplaceVideo ?: continue
            browseIds.add(video.id)
        }
        CatalogPrefsStore.applyBrowseOrder(this, browseIds)
    }

    private fun reloadCarousel(preferId: String?) {
        val browse = VideoCatalog.browseVideos(this)
        objectAdapter.clear()
        browse.forEach { objectAdapter.add(it) }
        applyEmptyOrHeroState(preferId)
        updateChrome()
    }

    private fun applyEmptyOrHeroState(preferId: String? = currentVideo?.id) {
        val browse = VideoCatalog.browseVideos(this)
        val emptyFavFilter = CatalogPrefsStore.isFavoritesOnly(this) && browse.isEmpty()
        if (emptyFavFilter) {
            emptyFavorites.visibility = View.VISIBLE
            heroRoot.visibility = View.INVISIBLE
            carousel.visibility = View.INVISIBLE
            carousel.isFocusable = false
            if (::heroPreview.isInitialized) heroPreview.release()
            currentVideo = null
            heroTitle.text = getString(R.string.browse_empty_favorites)
            heroMeta.text = ""
            emptyShowAllChip.post { emptyShowAllChip.requestFocus() }
            return
        }
        emptyFavorites.visibility = View.GONE
        heroRoot.visibility = View.VISIBLE
        carousel.visibility = View.VISIBLE
        carousel.isFocusable = true
        if (browse.isEmpty()) {
            currentVideo = null
            heroTitle.text = getString(R.string.app_name)
            heroMeta.text = ""
            if (::heroPreview.isInitialized) heroPreview.release()
            return
        }
        val pick = preferId?.let { id -> browse.firstOrNull { it.id == id } }
            ?: LastPlayedStore.resolveVideo(this).takeIf { v -> browse.any { it.id == v.id } }
            ?: browse.first()
        val index = browse.indexOfFirst { it.id == pick.id }.coerceAtLeast(0)
        bindHero(pick)
        carousel.post {
            settleOnIndex(index, smooth = false)
        }
    }

    private fun updateChrome() {
        val favoritesOnly = CatalogPrefsStore.isFavoritesOnly(this)
        filterAll.isSelected = !favoritesOnly
        filterFavorites.isSelected = favoritesOnly
        browseSubtitle.text = if (isMoveMode) {
            getString(R.string.browse_move_hint)
        } else {
            getString(R.string.browse_hint_actions)
        }
        val chipsEnabled = !isMoveMode && !cardMenu.isVisible
        filterAll.isEnabled = chipsEnabled
        filterFavorites.isEnabled = chipsEnabled
        soundOpenButton.isEnabled = chipsEnabled
        filterAll.isFocusable = chipsEnabled
        filterFavorites.isFocusable = chipsEnabled
        soundOpenButton.isFocusable = chipsEnabled
    }

    private fun focusActiveFilterSegment() {
        if (CatalogPrefsStore.isFavoritesOnly(this)) {
            filterFavorites.requestFocus()
        } else {
            filterAll.requestFocus()
        }
    }

    private fun focusCurrentCard() {
        if (!::carousel.isInitialized) return
        val pos = carousel.selectedPosition.coerceAtLeast(0)
        carousel.requestFocus()
        carousel.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
    }

    private fun wireChipFocus(vararg chips: TextView) {
        chips.forEach { chip ->
            chip.nextFocusDownId = R.id.video_carousel
        }
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
        private const val CAROUSEL_MOVE_SCALE_Y = 1.08f
        private const val SAFE_INSET_WIDTH_FRACTION = 0.07f
        /** Cap hero media width so the 16:9 box stays a preview, not near full-bleed. */
        private const val HERO_MAX_WIDTH_FRACTION = 0.52f
        /** Cap concurrent NewPipe probes so launch stays snappy on TV SoCs. */
        private const val AVAILABILITY_PARALLELISM = 4
    }
}
