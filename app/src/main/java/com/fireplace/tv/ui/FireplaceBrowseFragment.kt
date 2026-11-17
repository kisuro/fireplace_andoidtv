package com.fireplace.tv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import com.fireplace.tv.R
import com.fireplace.tv.data.FireplaceVideo
import com.fireplace.tv.data.VideoCatalog

class FireplaceBrowseFragment : BrowseSupportFragment() {

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
        loadRows()
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is FireplaceVideo) {
                startActivity(
                    Intent(requireContext(), PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_VIDEO_ID, item.id)
                    },
                )
            }
        }
    }

    private fun setupUi() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false
        brandColor = ContextCompat.getColor(requireContext(), R.color.fireplace_bg_mid)
        searchAffordanceColor = Color.TRANSPARENT
    }

    private fun loadRows() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val cardPresenter = VideoCardPresenter()
        val listRowAdapter = ArrayObjectAdapter(cardPresenter)
        VideoCatalog.videos.forEach { listRowAdapter.add(it) }

        val header = HeaderItem(0, getString(R.string.browse_subtitle))
        rowsAdapter.add(ListRow(header, listRowAdapter))
        adapter = rowsAdapter
    }
}
