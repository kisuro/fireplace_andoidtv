package com.kisuro.fireplace.tv

import android.app.Application
import com.kisuro.fireplace.tv.extract.OkHttpDownloader
import com.kisuro.fireplace.tv.playback.AmbientSoundController
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
        AmbientSoundController.install(this)
    }
}
