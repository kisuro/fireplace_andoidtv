package com.kisuro.fireplace.tv.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.kisuro.fireplace.tv.R
import com.kisuro.fireplace.tv.data.AmbientSound
import com.kisuro.fireplace.tv.data.AudioPrefsStore

/**
 * Leanback-friendly sound overlay: fireplace crackle mute + ambient pick + volume.
 * Ambient stays off until the user enables a nature sound (not the Off chip).
 */
class SoundPanelController(
    private val host: ViewGroup,
    private val onChanged: () -> Unit,
) {
    private val root: View = View.inflate(host.context, R.layout.overlay_sound_panel, null)
    private val crackleOn: TextView = root.findViewById(R.id.sound_crackle_on)
    private val crackleOff: TextView = root.findViewById(R.id.sound_crackle_off)
    private val ambientRow: LinearLayout = root.findViewById(R.id.sound_ambient_row)
    private val volumeSoft: TextView = root.findViewById(R.id.sound_volume_soft)
    private val volumeMedium: TextView = root.findViewById(R.id.sound_volume_medium)
    private val volumeLoud: TextView = root.findViewById(R.id.sound_volume_loud)
    private val volumeLabel: TextView = root.findViewById(R.id.sound_volume_label)
    private val volumeRow: View = root.findViewById(R.id.sound_volume_row)

    private val ambientChips = linkedMapOf<String, TextView>()
    private var ambientOffChip: TextView? = null
    private val chipGapPx = host.resources.getDimensionPixelSize(R.dimen.sound_chip_gap)

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

        crackleOn.setOnClickListener {
            AudioPrefsStore.setFireplaceMuted(host.context, false)
            refreshSelection()
            onChanged()
        }
        crackleOff.setOnClickListener {
            AudioPrefsStore.setFireplaceMuted(host.context, true)
            refreshSelection()
            onChanged()
        }

        ambientOffChip = addAmbientChip(host.context.getString(R.string.sound_off), KEY_OFF) {
            AudioPrefsStore.setAmbientEnabled(host.context, false)
            refreshSelection()
            onChanged()
        }
        AmbientSound.entries.forEach { sound ->
            addAmbientChip(host.context.getString(sound.titleRes), sound.id) {
                AudioPrefsStore.setAmbientSound(host.context, sound)
                AudioPrefsStore.setAmbientEnabled(host.context, true)
                refreshSelection()
                onChanged()
            }
        }

        volumeSoft.setOnClickListener { setVolume(VOLUME_SOFT) }
        volumeMedium.setOnClickListener { setVolume(VOLUME_MEDIUM) }
        volumeLoud.setOnClickListener { setVolume(VOLUME_LOUD) }
    }

    fun show() {
        refreshSelection()
        root.visibility = View.VISIBLE
        root.bringToFront()
        val focusTarget = when {
            AudioPrefsStore.isAmbientEnabled(host.context) ->
                ambientChips[AudioPrefsStore.getAmbientSound(host.context).id]
            else -> ambientOffChip
        } ?: crackleOn
        focusTarget.post { focusTarget.requestFocus() }
    }

    fun hide() {
        root.visibility = View.GONE
    }

    fun toggle() {
        if (isVisible) hide() else show()
    }

    private fun setVolume(volume: Float) {
        AudioPrefsStore.setAmbientVolume(host.context, volume)
        refreshSelection()
        onChanged()
    }

    private fun addAmbientChip(label: String, key: String, onClick: () -> Unit): TextView {
        val styled = TextView(host.context, null, 0, R.style.SoundChip)
        styled.text = label
        styled.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        if (ambientRow.childCount > 0) {
            lp.marginStart = chipGapPx
        }
        ambientRow.addView(styled, lp)
        ambientChips[key] = styled
        return styled
    }

    private fun refreshSelection() {
        val ctx = host.context
        val crackleMuted = AudioPrefsStore.isFireplaceMuted(ctx)
        crackleOn.isSelected = !crackleMuted
        crackleOff.isSelected = crackleMuted

        val ambientEnabled = AudioPrefsStore.isAmbientEnabled(ctx)
        val selectedSound = AudioPrefsStore.getAmbientSound(ctx)
        ambientChips.forEach { (key, chip) ->
            chip.isSelected = when (key) {
                KEY_OFF -> !ambientEnabled
                else -> ambientEnabled && key == selectedSound.id
            }
        }

        val volume = AudioPrefsStore.getAmbientVolume(ctx)
        volumeSoft.isSelected = volume <= 0.30f
        volumeMedium.isSelected = volume > 0.30f && volume < 0.70f
        volumeLoud.isSelected = volume >= 0.70f

        val volumeVisible = if (ambientEnabled) View.VISIBLE else View.GONE
        volumeLabel.visibility = volumeVisible
        volumeRow.visibility = volumeVisible
    }

    companion object {
        private const val KEY_OFF = "off"
        private const val VOLUME_SOFT = 0.25f
        private const val VOLUME_MEDIUM = 0.45f
        private const val VOLUME_LOUD = 0.75f
    }
}
