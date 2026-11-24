package com.kisuro.fireplace.tv.ui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.kisuro.fireplace.tv.R
import com.kisuro.fireplace.tv.data.AmbientSound
import com.kisuro.fireplace.tv.data.AudioPrefsStore

/**
 * Leanback-friendly sound overlay: fireplace crackle mute + ambient pick + volume slider.
 * Ambient stays off until the user enables a nature sound (not the Off chip).
 * Volume: focus the SeekBar and use Left/Right (persisted 0–100%).
 */
class SoundPanelController(
    private val host: ViewGroup,
    private val onChanged: () -> Unit,
) {
    private val root: View = View.inflate(host.context, R.layout.overlay_sound_panel, null)
    private val crackleOn: TextView = root.findViewById(R.id.sound_crackle_on)
    private val crackleOff: TextView = root.findViewById(R.id.sound_crackle_off)
    private val ambientRow: LinearLayout = root.findViewById(R.id.sound_ambient_row)
    private val volumeHeader: View = root.findViewById(R.id.sound_volume_header)
    private val volumeSeek: SeekBar = root.findViewById(R.id.sound_volume_seek)
    private val volumeValue: TextView = root.findViewById(R.id.sound_volume_value)
    private val volumeHint: TextView = root.findViewById(R.id.sound_volume_hint)

    private val ambientChips = linkedMapOf<String, TextView>()
    private var ambientOffChip: TextView? = null
    private val chipGapPx = host.resources.getDimensionPixelSize(R.dimen.sound_chip_gap)
    private var suppressSeekCallback: Boolean = false

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

        volumeSeek.max = VOLUME_STEPS
        volumeSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateVolumeLabel(progress)
                    if (suppressSeekCallback || !fromUser) return
                    persistVolume(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    persistVolume(volumeSeek.progress)
                }
            },
        )
        volumeSeek.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (volumeSeek.progress > 0) {
                        volumeSeek.progress = volumeSeek.progress - 1
                        persistVolume(volumeSeek.progress)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (volumeSeek.progress < volumeSeek.max) {
                        volumeSeek.progress = volumeSeek.progress + 1
                        persistVolume(volumeSeek.progress)
                    }
                    true
                }
                else -> false
            }
        }
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

    private fun persistVolume(steps: Int) {
        val volume = (steps.toFloat() / VOLUME_STEPS.toFloat()).coerceIn(0f, 1f)
        AudioPrefsStore.setAmbientVolume(host.context, volume)
        onChanged()
    }

    private fun updateVolumeLabel(steps: Int) {
        val percent = ((steps.toFloat() / VOLUME_STEPS.toFloat()) * 100f).toInt()
        volumeValue.text = host.context.getString(R.string.sound_volume_value, percent)
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
        val steps = (volume * VOLUME_STEPS).toInt().coerceIn(0, VOLUME_STEPS)
        suppressSeekCallback = true
        volumeSeek.progress = steps
        suppressSeekCallback = false
        updateVolumeLabel(steps)

        val volumeVisible = if (ambientEnabled) View.VISIBLE else View.GONE
        volumeHeader.visibility = volumeVisible
        volumeSeek.visibility = volumeVisible
        volumeHint.visibility = volumeVisible
    }

    companion object {
        private const val KEY_OFF = "off"
        /** 20 steps → 5% per Left/Right press. */
        private const val VOLUME_STEPS = 20
    }
}
