package com.kisuro.fireplace.tv.playback

import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

@UnstableApi
class FireplaceMediaCodecSelector(
    private val policyProvider: () -> Vp9Policy,
) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )
        if (!isVp9Mime(mimeType)) {
            return infos
        }

        return when (policyProvider()) {
            Vp9Policy.BAN_VP9 -> {
                Log.i(TAG, "BAN_VP9: no decoders for mime=$mimeType")
                emptyList()
            }
            Vp9Policy.DEMOTE_MTK_VP9 -> {
                val filtered = infos.filterNot { isMtkVp9Decoder(it.name) }
                if (filtered.size != infos.size) {
                    Log.i(
                        TAG,
                        "DEMOTE_MTK_VP9: removed ${infos.size - filtered.size} decoder(s) for $mimeType",
                    )
                }
                if (filtered.isEmpty() && infos.isNotEmpty()) {
                    Log.w(TAG, "Only MTK VP9 decoder available — treating VP9 as unsupported")
                }
                filtered
            }
        }
    }

    companion object {
        private const val TAG = "FireplaceCodec"

        fun isVp9Mime(mimeType: String): Boolean {
            val m = mimeType.lowercase()
            return MimeTypes.VIDEO_VP9.equals(mimeType, ignoreCase = true) ||
                "vp9" in m ||
                "vp09" in m
        }

        fun isMtkVp9Decoder(name: String): Boolean {
            val n = name.lowercase()
            return ("mtk" in n || "mediatek" in n) && ("vp9" in n || "vp09" in n)
        }
    }
}
