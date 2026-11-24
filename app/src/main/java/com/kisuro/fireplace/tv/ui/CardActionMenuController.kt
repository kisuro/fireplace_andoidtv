package com.kisuro.fireplace.tv.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.kisuro.fireplace.tv.R
import com.kisuro.fireplace.tv.data.CatalogPrefsStore
import com.kisuro.fireplace.tv.data.FireplaceVideo

/**
 * Small D-pad menu opened by short OK on a strip card:
 * Play, Add/Remove favorite, Move, Cancel.
 */
class CardActionMenuController(
    private val host: ViewGroup,
    private val onPlay: (FireplaceVideo) -> Unit,
    private val onToggleFavorite: (FireplaceVideo) -> Unit,
    private val onMove: (FireplaceVideo) -> Unit,
) {
    private val root: View = View.inflate(host.context, R.layout.overlay_card_menu, null)
    private val title: TextView = root.findViewById(R.id.card_menu_title)
    private val playChip: TextView = root.findViewById(R.id.card_menu_play)
    private val favoriteChip: TextView = root.findViewById(R.id.card_menu_favorite)
    private val moveChip: TextView = root.findViewById(R.id.card_menu_move)
    private val cancelChip: TextView = root.findViewById(R.id.card_menu_cancel)

    private var video: FireplaceVideo? = null

    val isVisible: Boolean
        get() = root.visibility == View.VISIBLE

    init {
        host.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.visibility = View.GONE

        playChip.nextFocusUpId = R.id.card_menu_cancel
        playChip.nextFocusDownId = R.id.card_menu_favorite
        favoriteChip.nextFocusUpId = R.id.card_menu_play
        favoriteChip.nextFocusDownId = R.id.card_menu_move
        moveChip.nextFocusUpId = R.id.card_menu_favorite
        moveChip.nextFocusDownId = R.id.card_menu_cancel
        cancelChip.nextFocusUpId = R.id.card_menu_move
        cancelChip.nextFocusDownId = R.id.card_menu_play

        playChip.setOnClickListener {
            val v = video ?: return@setOnClickListener
            hide()
            onPlay(v)
        }
        favoriteChip.setOnClickListener {
            val v = video ?: return@setOnClickListener
            onToggleFavorite(v)
            refreshFavoriteLabel(v)
        }
        moveChip.setOnClickListener {
            if (!moveChip.isEnabled) return@setOnClickListener
            val v = video ?: return@setOnClickListener
            hide()
            onMove(v)
        }
        cancelChip.setOnClickListener { hide() }
        root.setOnClickListener { hide() }
    }

    fun show(target: FireplaceVideo, canMove: Boolean = true) {
        video = target
        title.text = target.title
        refreshFavoriteLabel(target)
        moveChip.isEnabled = canMove
        moveChip.alpha = if (canMove) 1f else 0.4f
        root.visibility = View.VISIBLE
        root.bringToFront()
        playChip.post { playChip.requestFocus() }
    }

    fun hide() {
        root.visibility = View.GONE
        video = null
    }

    private fun refreshFavoriteLabel(target: FireplaceVideo) {
        val favored = CatalogPrefsStore.isFavorite(host.context, target.id)
        favoriteChip.text = host.context.getString(
            if (favored) R.string.card_menu_favorite_remove else R.string.card_menu_favorite_add,
        )
        favoriteChip.isSelected = favored
    }
}
