package com.kisuro.fireplace.tv.data

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import com.kisuro.fireplace.tv.R

/**
 * Bundled ambient nature loops (independent of YouTube fireplace audio).
 * Asset sourcing mirrors Exhalune's SoundFX.ru background sounds.
 */
enum class AmbientSound(
    val id: String,
    @StringRes val titleRes: Int,
    @RawRes val rawResId: Int,
) {
    RAIN("rain", R.string.ambient_rain, R.raw.ambient_rain),
    FOREST("forest", R.string.ambient_forest, R.raw.ambient_forest),
    THUNDERSTORM("thunderstorm", R.string.ambient_thunderstorm, R.raw.ambient_thunderstorm),
    STREAM("stream", R.string.ambient_stream, R.raw.ambient_stream),
    WIND("wind", R.string.ambient_wind, R.raw.ambient_wind),
    BIRDS("birds", R.string.ambient_birds, R.raw.ambient_birds),
    ;

    companion object {
        fun fromId(id: String?): AmbientSound {
            if (id.isNullOrBlank()) return RAIN
            return entries.firstOrNull { it.id == id } ?: RAIN
        }
    }
}
