package com.fireplace.tv.ui

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.Presenter

/**
 * Non-focusable leading/trailing gutter so the first/last video card cannot
 * sit at screen x=0 under HorizontalGridView LOW_EDGE alignment.
 */
class EdgeSpacerPresenter(
    private val widthDp: Float = WIDTH_DP,
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val widthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            widthDp,
            parent.resources.displayMetrics,
        ).toInt()
        val view = View(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(widthPx, 1)
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(0x00000000)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) = Unit

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit

    companion object {
        /** ~60dp → 120px at density 2; keeps focused card x1 ≳ 80–120. */
        const val WIDTH_DP = 64f
    }
}
