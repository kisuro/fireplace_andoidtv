package com.fireplace.tv

import android.app.Application
import com.fireplace.tv.extract.OkHttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class FireplaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(
            OkHttpDownloader(),
            Localization.DEFAULT,
            ContentCountry.DEFAULT,
        )
    }
}
