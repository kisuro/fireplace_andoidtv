package com.kisuro.fireplace.tv.data

import android.content.Context

/**
 * Persists carousel order, favorites, and the favorites-only filter.
 *
 * Order is a comma-separated id list (StringSet would lose sequence).
 * Missing catalog ids are appended; unknown ids are dropped on resolve.
 */
object CatalogPrefsStore {
    private const val PREFS = "fireplace_prefs"
    private const val KEY_ORDER = "catalog_order"
    private const val KEY_FAVORITES = "catalog_favorites"
    private const val KEY_FAVORITES_ONLY = "catalog_favorites_only"
    private const val SEP = ","

    fun resolveOrder(context: Context, catalogIds: List<String>): List<String> {
        val known = catalogIds.toSet()
        val saved = readList(context, KEY_ORDER).filter { it in known }
        val missing = catalogIds.filter { it !in saved.toSet() }
        return saved + missing
    }

    fun setOrder(context: Context, ids: List<String>) {
        writeList(context, KEY_ORDER, ids)
    }

    /** Swap two ids in the persisted full-catalog order. */
    fun swapInOrder(context: Context, idA: String, idB: String) {
        if (idA == idB) return
        val catalogIds = VideoCatalog.videos.map { it.id }
        val order = resolveOrder(context, catalogIds).toMutableList()
        val i = order.indexOf(idA)
        val j = order.indexOf(idB)
        if (i < 0 || j < 0) return
        order[i] = idB
        order[j] = idA
        setOrder(context, order)
    }

    fun getFavoriteIds(context: Context): Set<String> {
        return readList(context, KEY_FAVORITES).toSet()
    }

    fun isFavorite(context: Context, id: String): Boolean {
        return id in getFavoriteIds(context)
    }

    fun setFavorite(context: Context, id: String, favorite: Boolean) {
        val next = getFavoriteIds(context).toMutableSet()
        if (favorite) next.add(id) else next.remove(id)
        writeList(context, KEY_FAVORITES, next.toList())
    }

    /** @return new favorite state after toggle */
    fun toggleFavorite(context: Context, id: String): Boolean {
        val next = !isFavorite(context, id)
        setFavorite(context, id, next)
        return next
    }

    fun isFavoritesOnly(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FAVORITES_ONLY, false)
    }

    fun setFavoritesOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FAVORITES_ONLY, enabled).apply()
    }

    private fun readList(context: Context, key: String): List<String> {
        val raw = prefs(context).getString(key, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun writeList(context: Context, key: String, ids: List<String>) {
        prefs(context).edit().putString(key, ids.joinToString(SEP)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
